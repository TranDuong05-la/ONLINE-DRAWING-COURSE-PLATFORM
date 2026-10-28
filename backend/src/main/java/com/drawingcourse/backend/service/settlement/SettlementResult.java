package com.drawingcourse.backend.service.settlement;

/** Result of coordinating local settlement state; it never represents an external fulfillment result. */
public record SettlementResult(Outcome outcome, Integer orderId, Integer paymentEventId,
                               Integer settlementId, Reason reason) {
    public enum Outcome { SETTLED, ALREADY_SETTLED, REQUIRES_REVIEW, NOT_ELIGIBLE, RETRYABLE_FAILURE }
    public enum Reason {
        NONE, MISSING_EVENT, CONFLICTING_CALLBACK, NON_SUCCESS_CALLBACK, EVENT_NOT_APPLIED,
        ROUTE_MISMATCH, SETTLEMENT_CONFLICT, ORDER_NOT_ELIGIBLE, PAYMENT_EVIDENCE_REJECTED,
        TRANSIENT_DATABASE_FAILURE
    }
}
