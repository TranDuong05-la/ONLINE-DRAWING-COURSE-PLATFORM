package com.drawingcourse.backend.service.settlement;

import com.artacademy.payment.vnpay.IpnClassification;
import com.artacademy.payment.vnpay.IpnReconciliationResult;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.PaymentAttempt;
import com.drawingcourse.backend.entity.PaymentEvent;
import com.drawingcourse.backend.entity.PaymentEventAudit;
import com.drawingcourse.backend.entity.PaymentStatus;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.repository.PaymentEventRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Unregistered-by-default settlement preparation core. Every persistence and required participant call runs in
 * one REQUIRED local transaction. No gateway/network or remote fulfillment work is performed here.
 */
public final class SettlementCoordinator {
    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final PaymentEventRepository events;
    private final SettlementLedgerPort ledger;
    private final CourseOwnershipConfirmationPort courses;
    private final InventoryReservationConfirmationPort inventory;
    private final VoucherConsumptionPort vouchers;
    private final Clock clock;
    private final OrderPersistenceMapper orderMapper = new OrderPersistenceMapper();
    private final PaymentPersistenceMapper paymentMapper = new PaymentPersistenceMapper();
    private final TransactionTemplate transaction;

    public SettlementCoordinator(OrderRepository orders, PaymentAttemptRepository attempts,
            PaymentEventRepository events, SettlementLedgerPort ledger,
            CourseOwnershipConfirmationPort courses, InventoryReservationConfirmationPort inventory,
            VoucherConsumptionPort vouchers, Clock clock, PlatformTransactionManager transactionManager) {
        this.orders = Objects.requireNonNull(orders);
        this.attempts = Objects.requireNonNull(attempts);
        this.events = Objects.requireNonNull(events);
        this.ledger = Objects.requireNonNull(ledger);
        this.courses = Objects.requireNonNull(courses);
        this.inventory = Objects.requireNonNull(inventory);
        this.vouchers = Objects.requireNonNull(vouchers);
        this.clock = Objects.requireNonNull(clock);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** The persisted event is reloaded and checked; a client-supplied success flag is never accepted. */
    public SettlementResult settle(IpnReconciliationResult reconciliation) {
        Objects.requireNonNull(reconciliation, "reconciliation");
        if (reconciliation.classification() == IpnClassification.CONFLICTING_CALLBACK
                || reconciliation.conflictId() != null) {
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, reconciliation.orderId(),
                    reconciliation.eventId(), null, SettlementResult.Reason.CONFLICTING_CALLBACK);
        }
        if (reconciliation.eventId() == null) {
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, reconciliation.orderId(), null,
                    null, SettlementResult.Reason.MISSING_EVENT);
        }
        boolean participatingInOuterTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return transaction.execute(status -> settleInTransaction(reconciliation));
        } catch (TransientDataAccessException retryable) {
            // A REQUIRED template that joined a caller transaction cannot know that the outer owner
            // completed rollback. Propagate so no caller can mistake an unfinished rollback for retry-safe.
            if (participatingInOuterTransaction) throw retryable;
            return result(SettlementResult.Outcome.RETRYABLE_FAILURE, reconciliation.orderId(),
                    reconciliation.eventId(), null, SettlementResult.Reason.TRANSIENT_DATABASE_FAILURE);
        }
    }

    private SettlementResult settleInTransaction(IpnReconciliationResult input) {
        requireTransaction();
        var eventRow = events.findById(input.eventId()).orElse(null);
        if (eventRow == null) return result(SettlementResult.Outcome.REQUIRES_REVIEW, input.orderId(),
                input.eventId(), null, SettlementResult.Reason.MISSING_EVENT);

        // The immutable event supplies routing only; acquire mutable locks in Order -> PaymentAttempt order.
        var order = orders.findForUpdate(eventRow.getOrderId(), eventRow.getUserId()).orElse(null);
        if (order == null) return result(SettlementResult.Outcome.REQUIRES_REVIEW, eventRow.getOrderId(),
                eventRow.getId(), null, SettlementResult.Reason.ROUTE_MISMATCH);
        var attemptRow = attempts.findForUpdate(eventRow.getAttemptId(), eventRow.getUserId()).orElse(null);
        if (attemptRow == null || !eventRow.getOrderId().equals(order.getId())
                || !eventRow.getUserId().equals(order.getUserId())
                || !eventRow.getOrderId().equals(attemptRow.getOrderId())
                || !eventRow.getUserId().equals(attemptRow.getUserId())
                || !eventRow.getAttemptId().equals(attemptRow.getId())
                || !eventRow.getProvider().equals(attemptRow.getProvider())
                || !eventRow.getMerchantReference().equals(attemptRow.getMerchantReference())) {
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, eventRow.getOrderId(), eventRow.getId(),
                    null, SettlementResult.Reason.ROUTE_MISMATCH);
        }

        // Current locking read matters on MySQL REPEATABLE_READ after a concurrent request waited on the Order.
        var existing = ledger.findForUpdateByOrderId(order.getId());
        if (existing.isPresent()) {
            var record = existing.orElseThrow();
            if (record.orderId().equals(order.getId()) && record.paymentAttemptId().equals(attemptRow.getId())
                    && record.paymentEventId().equals(eventRow.getId())
                    && order.getStatus() == OrderStatus.CONFIRMED
                    && order.getPaymentStatus() == PaymentStatus.SUCCEEDED
                    && attemptRow.getStatus() == PaymentStatus.SUCCEEDED) {
                return result(SettlementResult.Outcome.ALREADY_SETTLED, order.getId(), eventRow.getId(),
                        record.id(), SettlementResult.Reason.NONE);
            }
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, order.getId(), eventRow.getId(),
                    record.id(), SettlementResult.Reason.SETTLEMENT_CONFLICT);
        }

        if (input.classification() != IpnClassification.VALID_ON_TIME_SUCCESS
                && input.classification() != IpnClassification.DUPLICATE_CALLBACK) {
            return result(input.classification() == IpnClassification.VALID_FAILURE
                            ? SettlementResult.Outcome.NOT_ELIGIBLE : SettlementResult.Outcome.REQUIRES_REVIEW,
                    order.getId(), eventRow.getId(), null,
                    input.classification() == IpnClassification.VALID_FAILURE
                            ? SettlementResult.Reason.NON_SUCCESS_CALLBACK : SettlementResult.Reason.EVENT_NOT_APPLIED);
        }

        PaymentEventAudit audit = paymentMapper.toDomain(eventRow);
        PaymentAttempt attempt = paymentMapper.toDomain(attemptRow);
        if (audit.disposition() != PaymentEventAudit.Disposition.APPLIED
                || audit.reason() != PaymentAttempt.ReviewReason.NONE
                || audit.afterStatus() != PaymentStatus.SUCCEEDED
                || audit.event().outcome() != PaymentEvent.Outcome.SUCCEEDED
                || !"vnpay-hmac-sha512;paydate=valid".equals(audit.event().verificationReference())) {
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, order.getId(), eventRow.getId(), null,
                    SettlementResult.Reason.EVENT_NOT_APPLIED);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || attempt.status() != PaymentStatus.SUCCEEDED) {
            return result(SettlementResult.Outcome.NOT_ELIGIBLE, order.getId(), eventRow.getId(), null,
                    SettlementResult.Reason.ORDER_NOT_ELIGIBLE);
        }
        try {
            order.confirmPayment(attempt, audit);
        } catch (IllegalStateException rejectedEvidence) {
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, order.getId(), eventRow.getId(), null,
                    SettlementResult.Reason.PAYMENT_EVIDENCE_REJECTED);
        }

        String paymentReference = audit.event().transactionReference();
        if (paymentReference == null || paymentReference.isBlank())
            throw new IllegalStateException("Verified successful event lacks a transaction reference");
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // Shared acquisition order: Order -> Attempt -> Voucher -> Products by ID -> Courses by ID -> Ledger.
        if (order.getVoucherCode() != null) {
            vouchers.consumeForOrder(order.getUserId(), order.getId(), paymentReference,
                    audit.event().occurredAt(), now);
        }
        var productIds = new TreeSet<Integer>();
        var courseIds = new TreeSet<Integer>();
        for (var item : order.getItems()) {
            if (item.getItemType() == OrderItemType.PRODUCT) productIds.add(item.getItemId());
            else if (item.getItemType() == OrderItemType.COURSE) courseIds.add(item.getItemId());
        }
        for (Integer productId : productIds)
            inventory.confirmProductForOrder(order.getUserId(), productId, order.getId(), now);
        for (Integer courseId : courseIds)
            courses.confirmCourseForOrder(order.getUserId(), courseId, order.getId(), now);

        var saved = ledger.insert(order.getId(), attemptRow.getId(), eventRow.getId(), audit.event().occurredAt(), now);
        if (saved == null || saved.id() == null || !order.getId().equals(saved.orderId())
                || !attemptRow.getId().equals(saved.paymentAttemptId())
                || !eventRow.getId().equals(saved.paymentEventId())) {
            throw new IllegalStateException("Settlement ledger returned an invalid persisted reference");
        }
        return result(SettlementResult.Outcome.SETTLED, order.getId(), eventRow.getId(), saved.id(),
                SettlementResult.Reason.NONE);
    }

    private static SettlementResult result(SettlementResult.Outcome outcome, Integer orderId, Integer eventId,
                                           Integer settlementId, SettlementResult.Reason reason) {
        return new SettlementResult(outcome, orderId, eventId, settlementId, reason);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Settlement coordination requires an active REQUIRED transaction");
    }
}
