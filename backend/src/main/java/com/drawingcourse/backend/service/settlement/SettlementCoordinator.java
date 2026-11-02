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
import com.drawingcourse.backend.repository.PaymentSettlementWorkClaimRepository;
import com.drawingcourse.backend.repository.PaymentSettlementWorkItemRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final SettlementTransactionBoundary transaction;
    private final PaymentSettlementWorkItemRepository workItems;
    private final PaymentSettlementWorkClaimRepository workClaims;

    public SettlementCoordinator(OrderRepository orders, PaymentAttemptRepository attempts,
            PaymentEventRepository events, SettlementLedgerPort ledger,
            CourseOwnershipConfirmationPort courses, InventoryReservationConfirmationPort inventory,
            VoucherConsumptionPort vouchers, Clock clock, PlatformTransactionManager transactionManager) {
        this(orders, attempts, events, ledger, courses, inventory, vouchers, clock,
                new SettlementTimeoutProperties(), transactionManager, null, null);
    }

    public SettlementCoordinator(OrderRepository orders, PaymentAttemptRepository attempts,
            PaymentEventRepository events, SettlementLedgerPort ledger,
            CourseOwnershipConfirmationPort courses, InventoryReservationConfirmationPort inventory,
            VoucherConsumptionPort vouchers, Clock clock, SettlementTimeoutProperties timeoutProperties,
            PlatformTransactionManager transactionManager) {
        this(orders, attempts, events, ledger, courses, inventory, vouchers, clock, timeoutProperties,
                transactionManager, null, null);
    }

    public SettlementCoordinator(OrderRepository orders, PaymentAttemptRepository attempts,
            PaymentEventRepository events, SettlementLedgerPort ledger,
            CourseOwnershipConfirmationPort courses, InventoryReservationConfirmationPort inventory,
            VoucherConsumptionPort vouchers, Clock clock, SettlementTimeoutProperties timeoutProperties,
            PlatformTransactionManager transactionManager, PaymentSettlementWorkItemRepository workItems,
            PaymentSettlementWorkClaimRepository workClaims) {
        this.orders = Objects.requireNonNull(orders);
        this.attempts = Objects.requireNonNull(attempts);
        this.events = Objects.requireNonNull(events);
        this.ledger = Objects.requireNonNull(ledger);
        this.courses = Objects.requireNonNull(courses);
        this.inventory = Objects.requireNonNull(inventory);
        this.vouchers = Objects.requireNonNull(vouchers);
        this.clock = Objects.requireNonNull(clock);
        this.transaction = new SettlementTransactionBoundary(transactionManager, timeoutProperties);
        this.workItems = workItems;
        this.workClaims = workClaims;
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
        try {
            return transaction.execute(() -> settleInTransaction(reconciliation, null, null));
        } catch (TransientDataAccessException retryable) {
            return result(SettlementResult.Outcome.RETRYABLE_FAILURE, reconciliation.orderId(),
                    reconciliation.eventId(), null, SettlementResult.Reason.TRANSIENT_DATABASE_FAILURE);
        }
    }

    /** Executes only a database-persisted V9 claim; this is intentionally not scheduler- or worker-registered. */
    public SettlementResult executeClaimedWork(SettlementWorkClaimService.Claim claim) {
        Objects.requireNonNull(claim, "claim");
        if (workItems == null || workClaims == null)
            throw new IllegalStateException("Claimed settlement execution is not configured");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Claimed settlement execution rejects an ambient transaction");
        if (claim.id() <= 0 || claim.paymentEventId() <= 0 || claim.leaseToken() == null
                || claim.workerId() == null || claim.attemptCount() < 1)
            throw new IllegalArgumentException("Invalid settlement work claim");
        try {
            return transaction.execute(() -> executeClaimInTransaction(claim));
        } catch (RuntimeException failure) {
            // The transaction template has returned only after rollback/commit handling. Resolve uncertain commit
            // outcomes from a fresh transaction; never blindly repeat participant effects.
            try {
                return transaction.execute(() -> resolveFailedExecution(claim, failure));
            } catch (RuntimeException unresolved) {
                unresolved.addSuppressed(failure);
                throw new SettlementOutcomeUnknownException("Settlement outcome could not be resolved safely", unresolved);
            }
        }
    }

    private SettlementResult executeClaimInTransaction(SettlementWorkClaimService.Claim claim) {
        requireTransaction();
        var work = workItems.findById(claim.id()).orElse(null); // non-locking identity read; Order remains first lock
        if (work == null || !Integer.valueOf(claim.paymentEventId()).equals(work.getPaymentEventId()))
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, null, claim.paymentEventId(), null,
                    SettlementResult.Reason.ROUTE_MISMATCH);
        boolean completedReplay = work.getState() == com.drawingcourse.backend.entity.PaymentSettlementWorkState.COMPLETED;
        if (!completedReplay && (work.getState() != com.drawingcourse.backend.entity.PaymentSettlementWorkState.LEASED
                || !claim.leaseToken().equals(work.getLeaseToken())
                || !claim.workerId().equals(work.getLeaseOwner())
                || claim.attemptCount() != work.getAttemptCount()
                || claim.retryGeneration() != work.getRetryGeneration()))
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, null, claim.paymentEventId(), null,
                    SettlementResult.Reason.STALE_WORK_CLAIM);

        var event = events.findById(work.getPaymentEventId()).orElse(null);
        if (event == null) return result(SettlementResult.Outcome.REQUIRES_REVIEW, null,
                work.getPaymentEventId(), null, SettlementResult.Reason.MISSING_EVENT);
        if (completedReplay) return verifyCompletedReplay(event);
        var trustedInput = new IpnReconciliationResult(IpnClassification.VALID_ON_TIME_SUCCESS,
                event.getAttemptId(), event.getOrderId(), event.getId(), null,
                PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE, false);
        SettlementResult result = settleInTransaction(trustedInput, completedReplay ? null : claim.id(),
                completedReplay ? null : claim.leaseToken());
        if (result.outcome() == SettlementResult.Outcome.SETTLED
                || result.outcome() == SettlementResult.Outcome.ALREADY_SETTLED) return result;

        String failureCode = switch (result.reason()) {
            case SETTLEMENT_CONFLICT -> "SETTLEMENT_CONFLICT";
            case ORDER_NOT_ELIGIBLE -> "ORDER_NOT_ELIGIBLE";
            case NON_SUCCESS_CALLBACK, EVENT_NOT_APPLIED, MISSING_EVENT, PAYMENT_EVIDENCE_REJECTED,
                    ROUTE_MISMATCH -> "EVENT_NOT_ELIGIBLE";
            default -> "UNCLASSIFIED_FAILURE";
        };
        // A terminal business decision is made durable while this exact unexpired claim is still owned.
        if (workClaims.requireManualReview(claim.id(), claim.leaseToken(), failureCode) != 1)
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, result.orderId(), result.paymentEventId(),
                    result.settlementId(), SettlementResult.Reason.STALE_WORK_CLAIM);
        return result;
    }

    private SettlementResult verifyCompletedReplay(com.drawingcourse.backend.entity.PaymentEventJpaEntity event) {
        var order = orders.findForUpdate(event.getOrderId(), event.getUserId()).orElse(null);
        if (order == null) return result(SettlementResult.Outcome.REQUIRES_REVIEW, event.getOrderId(), event.getId(), null,
                SettlementResult.Reason.ROUTE_MISMATCH);
        var attempt = attempts.findForUpdate(event.getAttemptId(), event.getUserId()).orElse(null);
        if (attempt == null || !order.getId().equals(attempt.getOrderId())
                || !order.getUserId().equals(attempt.getUserId()))
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, event.getOrderId(), event.getId(), null,
                    SettlementResult.Reason.ROUTE_MISMATCH);
        var existing = ledger.findForUpdateByOrderId(order.getId());
        if (existing.isPresent()) {
            var settlement = existing.orElseThrow();
            if (settlement.orderId().equals(order.getId())
                    && settlement.paymentAttemptId().equals(attempt.getId())
                    && settlement.paymentEventId().equals(event.getId())
                    && order.getStatus() == OrderStatus.CONFIRMED
                    && order.getPaymentStatus() == PaymentStatus.SUCCEEDED
                    && attempt.getStatus() == PaymentStatus.SUCCEEDED)
                return result(SettlementResult.Outcome.ALREADY_SETTLED, order.getId(), event.getId(),
                        settlement.id(), SettlementResult.Reason.NONE);
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, order.getId(), event.getId(), settlement.id(),
                    SettlementResult.Reason.SETTLEMENT_CONFLICT);
        }
        return result(SettlementResult.Outcome.REQUIRES_REVIEW, order.getId(), event.getId(), null,
                SettlementResult.Reason.COMMIT_OUTCOME_UNKNOWN);
    }

    private SettlementResult resolveFailedExecution(SettlementWorkClaimService.Claim claim, RuntimeException failure) {
        var work = workItems.findById(claim.id()).orElse(null);
        if (work == null || !Integer.valueOf(claim.paymentEventId()).equals(work.getPaymentEventId()))
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, null, claim.paymentEventId(), null,
                    SettlementResult.Reason.COMMIT_OUTCOME_UNKNOWN);
        var event = events.findById(work.getPaymentEventId()).orElse(null);
        if (event == null) return result(SettlementResult.Outcome.REQUIRES_REVIEW, null,
                claim.paymentEventId(), null, SettlementResult.Reason.COMMIT_OUTCOME_UNKNOWN);
        var order = orders.findForUpdate(event.getOrderId(), event.getUserId()).orElse(null);
        if (order == null) return result(SettlementResult.Outcome.REQUIRES_REVIEW, event.getOrderId(), event.getId(), null,
                SettlementResult.Reason.COMMIT_OUTCOME_UNKNOWN);
        var attempt = attempts.findForUpdate(event.getAttemptId(), event.getUserId()).orElse(null);
        if (attempt == null) return result(SettlementResult.Outcome.REQUIRES_REVIEW, event.getOrderId(), event.getId(), null,
                SettlementResult.Reason.COMMIT_OUTCOME_UNKNOWN);
        var settlement = ledger.findForUpdateByOrderId(event.getOrderId());
        if (settlement.isPresent()) {
            var row = settlement.orElseThrow();
            if (row.paymentEventId().equals(event.getId()) && row.paymentAttemptId().equals(event.getAttemptId())
                    && work.getState() == com.drawingcourse.backend.entity.PaymentSettlementWorkState.COMPLETED
                    && order.getId().equals(event.getOrderId()) && order.getUserId().equals(event.getUserId())
                    && attempt.getId().equals(event.getAttemptId()) && attempt.getOrderId().equals(order.getId())
                    && order.getStatus() == OrderStatus.CONFIRMED
                    && order.getPaymentStatus() == PaymentStatus.SUCCEEDED
                    && attempt.getStatus() == PaymentStatus.SUCCEEDED)
                return result(SettlementResult.Outcome.ALREADY_SETTLED, event.getOrderId(), event.getId(), row.id(),
                        SettlementResult.Reason.NONE);
            return result(SettlementResult.Outcome.REQUIRES_REVIEW, event.getOrderId(), event.getId(), row.id(),
                    SettlementResult.Reason.COMMIT_OUTCOME_UNKNOWN);
        }
        if (failure instanceof TransientDataAccessException
                && workClaims.ownsUnexpiredLease(claim.id(), claim.leaseToken()))
            return result(SettlementResult.Outcome.RETRYABLE_FAILURE, event.getOrderId(), event.getId(), null,
                    SettlementResult.Reason.TRANSIENT_DATABASE_FAILURE);
        if (workClaims.ownsUnexpiredLease(claim.id(), claim.leaseToken()))
            workClaims.requireManualReview(claim.id(), claim.leaseToken(), "UNCLASSIFIED_FAILURE");
        return result(SettlementResult.Outcome.REQUIRES_REVIEW, event.getOrderId(), event.getId(), null,
                SettlementResult.Reason.COMMIT_OUTCOME_UNKNOWN);
    }

    private SettlementResult settleInTransaction(IpnReconciliationResult input, Integer claimedWorkId,
                                                  String leaseToken) {
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
                if (claimedWorkId != null && workClaims.completeSettlement(claimedWorkId, leaseToken) != 1)
                    throw new SettlementLeaseFenceException("Settlement work lease was stale or expired at completion");
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
                    || !audit.fingerprint().equals(audit.event().fingerprint())
                    || !"vnpay".equals(audit.event().provider())
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
        if (claimedWorkId != null) {
            // Flush all ORM changes before the token/UTC-expiry fence, which is the final application write.
            orders.saveAndFlush(order);
            if (workClaims.completeSettlement(claimedWorkId, leaseToken) != 1)
                throw new SettlementLeaseFenceException("Settlement work lease was stale or expired at completion");
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
