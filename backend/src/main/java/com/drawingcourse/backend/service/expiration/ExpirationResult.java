package com.drawingcourse.backend.service.expiration;

/** Outcome of local unpaid-Order expiration; it never denotes a refund or payment settlement. */
public record ExpirationResult(Outcome outcome, Integer orderId, Reason reason) {
    public enum Outcome { EXPIRED, ALREADY_EXPIRED, NOT_DUE, NOT_ELIGIBLE, REQUIRES_REVIEW, RETRYABLE_FAILURE }
    public enum Reason {
        NONE, ORDER_NOT_FOUND, ORDER_STATE, PAYMENT_DEADLINE_MISSING, PAYMENT_EVIDENCE,
        SETTLEMENT_EXISTS, TRANSIENT_DATABASE_FAILURE
    }
}
