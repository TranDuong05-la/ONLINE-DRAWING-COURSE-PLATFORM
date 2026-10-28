package com.drawingcourse.backend.entity;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OrderConfirmationTest {
    private static final int USER_ID = 301;
    private static final int ORDER_ID = 401;
    private static final int ATTEMPT_ID = 501;
    private static final int EVENT_ID = 601;
    private static final Instant CREATED = Instant.parse("2026-10-28T03:00:00.000000Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void confirmsPendingOrderFromPersistedAppliedOnTimeSuccessEvidence() {
        var fixture = fixture(PaymentEvent.Outcome.SUCCEEDED, PaymentStatus.SUCCEEDED,
                PaymentAttempt.ReviewReason.NONE, PaymentEventAudit.Disposition.APPLIED,
                PaymentAttempt.ReviewReason.NONE, "VND", 1000, CREATED.plusSeconds(10), CREATED.plusSeconds(11));

        var confirmed = fixture.order.confirmPayment(fixture.attempt, fixture.audit);

        assertEquals(OrderStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, confirmed.getPaymentStatus());
        assertEquals(fixture.order.getItems(), confirmed.getItems());
        assertEquals(fixture.order.getTotalVnd(), confirmed.getTotalVnd());
        assertEquals(fixture.order.getExpiresAt(), confirmed.getExpiresAt());
    }

    @Test
    void rejectsDuplicateConfirmation() {
        var fixture = fixture(PaymentEvent.Outcome.SUCCEEDED, PaymentStatus.SUCCEEDED,
                PaymentAttempt.ReviewReason.NONE, PaymentEventAudit.Disposition.APPLIED,
                PaymentAttempt.ReviewReason.NONE, "VND", 1000, CREATED.plusSeconds(10), CREATED.plusSeconds(11));
        var confirmed = fixture.order.confirmPayment(fixture.attempt, fixture.audit);
        assertThrows(IllegalStateException.class, () -> confirmed.confirmPayment(fixture.attempt, fixture.audit));
    }

    @Test
    void rejectsProviderPaymentExactlyAtExclusiveDeadline() {
        var order = order();
        Instant deadline = order.getExpiresAt();
        var fixture = fixture(order, PaymentEvent.Outcome.SUCCEEDED, PaymentStatus.SUCCEEDED,
                PaymentAttempt.ReviewReason.NONE, PaymentEventAudit.Disposition.APPLIED,
                PaymentAttempt.ReviewReason.NONE, "VND", 1000, deadline, deadline.plusSeconds(1));
        assertThrows(IllegalStateException.class, () -> fixture.order.confirmPayment(fixture.attempt, fixture.audit));
    }

    @Test
    void rejectsLateProviderPaymentEvenWhenCallbackArrivesBeforeDeadline() {
        var order = order();
        Instant deadline = order.getExpiresAt();
        var fixture = fixture(order, PaymentEvent.Outcome.SUCCEEDED, PaymentStatus.SUCCEEDED,
                PaymentAttempt.ReviewReason.LATE_SUCCESS, PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,
                PaymentAttempt.ReviewReason.LATE_SUCCESS, "VND", 1000, deadline, deadline);
        assertThrows(IllegalStateException.class, () -> fixture.order.confirmPayment(fixture.attempt, fixture.audit));
    }

    @Test
    void rejectsCallbackReceivedExactlyAtExclusiveDeadline() {
        var order = order();
        Instant deadline = order.getExpiresAt();
        var fixture = fixture(order, PaymentEvent.Outcome.SUCCEEDED, PaymentStatus.SUCCEEDED,
                PaymentAttempt.ReviewReason.LATE_SUCCESS, PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,
                PaymentAttempt.ReviewReason.LATE_SUCCESS, "VND", 1000, deadline.minusSeconds(1), deadline);
        assertThrows(IllegalStateException.class, () -> fixture.order.confirmPayment(fixture.attempt, fixture.audit));
    }

    @Test
    void rejectsAmountAndCurrencyMismatchAgainstOrderSnapshot() {
        var amountMismatch = fixture(PaymentEvent.Outcome.SUCCEEDED, PaymentStatus.SUCCEEDED,
                PaymentAttempt.ReviewReason.NONE, PaymentEventAudit.Disposition.APPLIED,
                PaymentAttempt.ReviewReason.NONE, "VND", 999, CREATED.plusSeconds(10), CREATED.plusSeconds(11));
        assertThrows(IllegalStateException.class,
                () -> amountMismatch.order.confirmPayment(amountMismatch.attempt, amountMismatch.audit));

        var currencyMismatch = fixture(PaymentEvent.Outcome.SUCCEEDED, PaymentStatus.SUCCEEDED,
                PaymentAttempt.ReviewReason.NONE, PaymentEventAudit.Disposition.APPLIED,
                PaymentAttempt.ReviewReason.NONE, "USD", 1000, CREATED.plusSeconds(10), CREATED.plusSeconds(11));
        assertThrows(IllegalStateException.class,
                () -> currencyMismatch.order.confirmPayment(currencyMismatch.attempt, currencyMismatch.audit));
    }

    @Test
    void rejectsUnknownAndConflictingPaymentEvidence() {
        var unknown = fixture(PaymentEvent.Outcome.UNKNOWN, PaymentStatus.UNKNOWN,
                PaymentAttempt.ReviewReason.NONE, PaymentEventAudit.Disposition.APPLIED,
                PaymentAttempt.ReviewReason.NONE, "VND", 1000, CREATED.plusSeconds(10), CREATED.plusSeconds(11));
        assertThrows(IllegalStateException.class, () -> unknown.order.confirmPayment(unknown.attempt, unknown.audit));

        var conflict = fixture(PaymentEvent.Outcome.SUCCEEDED, PaymentStatus.SUCCEEDED,
                PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS, PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,
                PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS, "VND", 1000,
                CREATED.plusSeconds(10), CREATED.plusSeconds(11));
        assertThrows(IllegalStateException.class, () -> conflict.order.confirmPayment(conflict.attempt, conflict.audit));
    }

    @Test
    void rejectsUnpersistedOrMisroutedEvidence() {
        var fixture = fixture(PaymentEvent.Outcome.SUCCEEDED, PaymentStatus.SUCCEEDED,
                PaymentAttempt.ReviewReason.NONE, PaymentEventAudit.Disposition.APPLIED,
                PaymentAttempt.ReviewReason.NONE, "VND", 1000, CREATED.plusSeconds(10), CREATED.plusSeconds(11));
        var unpersisted = new PaymentEventAudit(null, fixture.audit.attemptId(), fixture.audit.orderId(), USER_ID,
                fixture.audit.event(), fixture.audit.fingerprint(), fixture.audit.beforeStatus(), fixture.audit.afterStatus(),
                fixture.audit.disposition(), fixture.audit.reason(), fixture.audit.observedOrderStatus());
        assertThrows(IllegalStateException.class, () -> fixture.order.confirmPayment(fixture.attempt, unpersisted));

        var mismatched = new PaymentAttempt(ATTEMPT_ID, ORDER_ID + 1, USER_ID, fixture.attempt.provider(),
                fixture.attempt.merchantReference(), fixture.attempt.expectedAmountVnd(), fixture.attempt.currency(),
                fixture.attempt.orderCreatedAt(), fixture.attempt.expiresAt(), fixture.attempt.createdAt(),
                fixture.attempt.updatedAt(), fixture.attempt.status(), fixture.attempt.transactionReference(),
                fixture.attempt.succeededAt(), fixture.attempt.reviewReason());
        assertThrows(IllegalStateException.class, () -> fixture.order.confirmPayment(mismatched, fixture.audit));
    }

    @Test
    void rejectsEvidenceWithoutVerifiedValidProviderTimestampMarker() {
        var fixture = fixture(PaymentEvent.Outcome.SUCCEEDED, PaymentStatus.SUCCEEDED,
                PaymentAttempt.ReviewReason.NONE, PaymentEventAudit.Disposition.APPLIED,
                PaymentAttempt.ReviewReason.NONE, "VND", 1000, CREATED.plusSeconds(10), CREATED.plusSeconds(11));
        var invalidEvent = new PaymentEvent("vnpay", fixture.audit.event().eventReference(),
                fixture.audit.event().merchantReference(), fixture.audit.event().transactionReference(),
                fixture.audit.event().amountVnd(), fixture.audit.event().currency(), fixture.audit.event().outcome(),
                fixture.audit.event().occurredAt(), fixture.audit.event().receivedAt(), DIGEST,
                "vnpay-hmac-sha512;paydate=invalid");
        var invalidAudit = new PaymentEventAudit(EVENT_ID, ATTEMPT_ID, ORDER_ID, USER_ID, invalidEvent,
                invalidEvent.fingerprint(), PaymentStatus.PENDING, PaymentStatus.SUCCEEDED,
                PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE,
                OrderStatus.PENDING_PAYMENT);

        assertThrows(IllegalStateException.class,
                () -> fixture.order.confirmPayment(fixture.attempt, invalidAudit));
    }

    private static Fixture fixture(PaymentEvent.Outcome outcome, PaymentStatus attemptStatus,
                                   PaymentAttempt.ReviewReason attemptReason,
                                   PaymentEventAudit.Disposition disposition,
                                   PaymentAttempt.ReviewReason auditReason, String currency, long amount,
                                   Instant paidAt, Instant receivedAt) {
        return fixture(order(), outcome, attemptStatus, attemptReason, disposition, auditReason, currency, amount, paidAt, receivedAt);
    }

    private static Fixture fixture(Order order, PaymentEvent.Outcome outcome, PaymentStatus attemptStatus,
                                   PaymentAttempt.ReviewReason attemptReason,
                                   PaymentEventAudit.Disposition disposition,
                                   PaymentAttempt.ReviewReason auditReason, String currency, long amount,
                                   Instant paidAt, Instant receivedAt) {
        boolean succeeded = attemptStatus == PaymentStatus.SUCCEEDED;
        String transactionReference = succeeded ? "txn-verified" : null;
        var attempt = new PaymentAttempt(ATTEMPT_ID, ORDER_ID, USER_ID, "vnpay", "merchant-verified",
                amount, "VND", order.getCreatedAt(), order.getExpiresAt(), order.getCreatedAt().plusSeconds(2),
                receivedAt, attemptStatus, transactionReference, succeeded ? paidAt : null, attemptReason);
        var event = new PaymentEvent("vnpay", "merchant-verified:txn-verified", "merchant-verified",
                transactionReference, amount, currency, outcome, paidAt, receivedAt, DIGEST,
                "vnpay-hmac-sha512;paydate=valid");
        var audit = new PaymentEventAudit(EVENT_ID, ATTEMPT_ID, ORDER_ID, USER_ID, event, event.fingerprint(),
                PaymentStatus.PENDING, attemptStatus, disposition, auditReason, OrderStatus.PENDING_PAYMENT);
        return new Fixture(order, attempt, audit);
    }

    private static Order order() {
        return Order.create(USER_ID, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 75,
                        "Test product", 1, 1000, true)), 0, Optional.empty(),
                Clock.fixed(CREATED, ZoneOffset.UTC));
    }

    private record Fixture(Order order, PaymentAttempt attempt, PaymentEventAudit audit) { }
}
