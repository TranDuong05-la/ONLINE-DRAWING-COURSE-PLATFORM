package com.drawingcourse.backend.service.expiration;

import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.PaymentAttempt;
import com.drawingcourse.backend.entity.PaymentEvent;
import com.drawingcourse.backend.entity.PaymentEventAudit;
import com.drawingcourse.backend.entity.PaymentStatus;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.repository.PaymentEventConflictRepository;
import com.drawingcourse.backend.repository.PaymentEventRepository;
import com.drawingcourse.backend.repository.PaymentSettlementRepository;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Unregistered-by-default Order expiry core. It releases only clean unpaid Orders and keeps all local writes
 * in one REQUIRED transaction. It never changes PaymentAttempt/Event evidence or initiates a refund.
 */
public final class OrderExpirationCoordinator {
    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final PaymentEventRepository events;
    private final PaymentEventConflictRepository conflicts;
    private final PaymentSettlementRepository settlements;
    private final VoucherExpirationReleasePort vouchers;
    private final InventoryReservationReleasePort inventory;
    private final CoursePurchaseReleasePort courses;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public OrderExpirationCoordinator(OrderRepository orders, PaymentAttemptRepository attempts,
            PaymentEventRepository events, PaymentEventConflictRepository conflicts,
            PaymentSettlementRepository settlements, VoucherExpirationReleasePort vouchers,
            InventoryReservationReleasePort inventory, CoursePurchaseReleasePort courses,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.orders = Objects.requireNonNull(orders);
        this.attempts = Objects.requireNonNull(attempts);
        this.events = Objects.requireNonNull(events);
        this.conflicts = Objects.requireNonNull(conflicts);
        this.settlements = Objects.requireNonNull(settlements);
        this.vouchers = Objects.requireNonNull(vouchers);
        this.inventory = Objects.requireNonNull(inventory);
        this.courses = Objects.requireNonNull(courses);
        this.clock = Objects.requireNonNull(clock);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** Uses the trusted application Clock; callers cannot supply an arbitrary expiration time. */
    public ExpirationResult expire(Integer orderId) {
        if (orderId == null || orderId <= 0) throw new IllegalArgumentException("Order ID must be positive");
        boolean participatingInOuterTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return transaction.execute(status -> expireInTransaction(orderId,
                    clock.instant().truncatedTo(ChronoUnit.MICROS)));
        } catch (TransientDataAccessException retryable) {
            // Do not report retryability until this coordinator has completed rollback.
            if (participatingInOuterTransaction) throw retryable;
            return new ExpirationResult(ExpirationResult.Outcome.RETRYABLE_FAILURE, orderId,
                    ExpirationResult.Reason.TRANSIENT_DATABASE_FAILURE);
        }
    }

    private ExpirationResult expireInTransaction(Integer orderId, Instant now) {
        requireTransaction();
        Integer userId = orders.findUserIdById(orderId).orElse(null);
        if (userId == null) return result(ExpirationResult.Outcome.NOT_ELIGIBLE, orderId,
                ExpirationResult.Reason.ORDER_NOT_FOUND);

        // Global mutable lock order: Order -> all PaymentAttempts by ID -> settlement -> Voucher -> Products -> Courses.
        var order = orders.findForUpdate(orderId, userId).orElse(null);
        if (order == null) return result(ExpirationResult.Outcome.NOT_ELIGIBLE, orderId,
                ExpirationResult.Reason.ORDER_NOT_FOUND);
        var orderAttempts = attempts.findAllForOrderUpdate(orderId, userId);
        var settlement = settlements.findForUpdateByOrderId(orderId);
        // These are current locking reads too: a plain RR snapshot could miss an IPN/conflict committed
        // while expiry waited for the Order/Attempt locks.
        var orderEvents = events.findAllForOrderUpdate(orderId, userId);
        var eventConflicts = conflicts.findAllForOrderUpdate(orderId, userId);

        if (order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.COMPLETED
                || order.getStatus() == OrderStatus.CANCELLED) {
            return result(ExpirationResult.Outcome.NOT_ELIGIBLE, orderId, ExpirationResult.Reason.ORDER_STATE);
        }
        if (settlement.isPresent()) {
            return result(ExpirationResult.Outcome.REQUIRES_REVIEW, orderId,
                    ExpirationResult.Reason.SETTLEMENT_EXISTS);
        }
        if (hasUnresolvedPaymentEvidence(order.getPaymentStatus(), orderAttempts, orderEvents, eventConflicts)) {
            return result(ExpirationResult.Outcome.REQUIRES_REVIEW, orderId,
                    ExpirationResult.Reason.PAYMENT_EVIDENCE);
        }
        if (order.getStatus() == OrderStatus.EXPIRED) {
            return order.getPaymentStatus() == PaymentStatus.EXPIRED
                    ? result(ExpirationResult.Outcome.ALREADY_EXPIRED, orderId, ExpirationResult.Reason.NONE)
                    : result(ExpirationResult.Outcome.REQUIRES_REVIEW, orderId, ExpirationResult.Reason.ORDER_STATE);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return result(ExpirationResult.Outcome.NOT_ELIGIBLE, orderId, ExpirationResult.Reason.ORDER_STATE);
        }
        Instant deadline = order.getPaymentExpiresAt();
        if (deadline == null) return result(ExpirationResult.Outcome.REQUIRES_REVIEW, orderId,
                ExpirationResult.Reason.PAYMENT_DEADLINE_MISSING);
        if (now.isBefore(deadline)) return result(ExpirationResult.Outcome.NOT_DUE, orderId,
                ExpirationResult.Reason.NONE);

        // An Order-level PENDING/UNKNOWN/SUCCEEDED status is unresolved or paid, even if its attempt rows disagree.
        if (order.getPaymentStatus() == PaymentStatus.PENDING || order.getPaymentStatus() == PaymentStatus.UNKNOWN
                || order.getPaymentStatus() == PaymentStatus.SUCCEEDED || order.getPaymentStatus() == PaymentStatus.EXPIRED) {
            return result(ExpirationResult.Outcome.REQUIRES_REVIEW, orderId,
                    ExpirationResult.Reason.PAYMENT_EVIDENCE);
        }

        order.expireUnpaid(now);
        vouchers.releaseVoucherForExpiredOrder(userId, orderId, now);

        var productIds = new TreeSet<Integer>();
        var courseIds = new TreeSet<Integer>();
        for (var item : order.getItems()) {
            if (item.getItemType() == OrderItemType.PRODUCT) productIds.add(item.getItemId());
            else if (item.getItemType() == OrderItemType.COURSE) courseIds.add(item.getItemId());
        }
        for (Integer productId : productIds)
            inventory.releaseProductForExpiredOrder(userId, productId, orderId, now);
        for (Integer courseId : courseIds)
            courses.releaseCourseForExpiredOrder(userId, courseId, orderId, now);

        return result(ExpirationResult.Outcome.EXPIRED, orderId, ExpirationResult.Reason.NONE);
    }

    private static boolean hasUnresolvedPaymentEvidence(PaymentStatus orderPaymentStatus,
            java.util.List<com.drawingcourse.backend.entity.PaymentAttemptJpaEntity> attempts,
            java.util.List<com.drawingcourse.backend.entity.PaymentEventJpaEntity> events,
            java.util.List<com.drawingcourse.backend.entity.PaymentEventConflictJpaEntity> conflicts) {
        if (orderPaymentStatus == PaymentStatus.PENDING || orderPaymentStatus == PaymentStatus.UNKNOWN
                || orderPaymentStatus == PaymentStatus.SUCCEEDED) return true;
        if (!conflicts.isEmpty()) return true;
        for (var attempt : attempts) {
            if (attempt.getStatus() == PaymentStatus.PENDING || attempt.getStatus() == PaymentStatus.UNKNOWN
                    || attempt.getStatus() == PaymentStatus.SUCCEEDED
                    || attempt.getReviewReason() != PaymentAttempt.ReviewReason.NONE) return true;
        }
        for (var event : events) {
            if (event.getOutcome() == PaymentEvent.Outcome.SUCCEEDED
                    || event.getOutcome() == PaymentEvent.Outcome.UNKNOWN
                    || event.getOutcome() == PaymentEvent.Outcome.PENDING
                    || event.getDisposition() == PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED
                    || event.getReason() != PaymentAttempt.ReviewReason.NONE
                    || event.getAfterStatus() == PaymentStatus.SUCCEEDED
                    || event.getAfterStatus() == PaymentStatus.UNKNOWN
                    || event.getAfterStatus() == PaymentStatus.PENDING) return true;
        }
        return false;
    }

    private static ExpirationResult result(ExpirationResult.Outcome outcome, Integer orderId,
                                           ExpirationResult.Reason reason) {
        return new ExpirationResult(outcome, orderId, reason);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Order expiration requires an active REQUIRED transaction");
    }
}
