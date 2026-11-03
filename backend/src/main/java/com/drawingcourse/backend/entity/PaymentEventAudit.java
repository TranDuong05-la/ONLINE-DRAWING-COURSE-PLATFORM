package com.drawingcourse.backend.entity;

import java.util.Objects;

/** Immutable first-event decision. Does not store raw payloads, card data or credentials. */
public record PaymentEventAudit(Integer id, Integer attemptId, Integer orderId, Integer userId,
                                PaymentEvent event, String fingerprint, PaymentStatus beforeStatus,
                                PaymentStatus afterStatus, Disposition disposition,
                                PaymentAttempt.ReviewReason reason, OrderStatus observedOrderStatus) {
    public enum Disposition { APPLIED, IGNORED, RECONCILIATION_REQUIRED }
    public PaymentEventAudit {
        Objects.requireNonNull(attemptId); Objects.requireNonNull(orderId); Objects.requireNonNull(userId);
        Objects.requireNonNull(event); Objects.requireNonNull(beforeStatus); Objects.requireNonNull(afterStatus);
        Objects.requireNonNull(disposition); Objects.requireNonNull(reason); Objects.requireNonNull(observedOrderStatus);
        if (fingerprint==null || !fingerprint.matches("v1:[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid event fingerprint");
        boolean unknownOutcomeReview = disposition==Disposition.RECONCILIATION_REQUIRED
                && reason==PaymentAttempt.ReviewReason.NONE && event.outcome()==PaymentEvent.Outcome.UNKNOWN;
        if (!unknownOutcomeReview
                && ((disposition==Disposition.RECONCILIATION_REQUIRED) != (reason!=PaymentAttempt.ReviewReason.NONE)))
            throw new IllegalArgumentException("Inconsistent event decision");
    }
}
