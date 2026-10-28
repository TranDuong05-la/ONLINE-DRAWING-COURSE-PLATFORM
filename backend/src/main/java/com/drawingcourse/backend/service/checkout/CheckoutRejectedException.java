package com.drawingcourse.backend.service.checkout;

/** Typed business conflict for the future HTTP boundary. No controller/HTTP mapping is activated in this phase. */
public final class CheckoutRejectedException extends RuntimeException {
    public enum Reason {
        INVALID_IDEMPOTENCY_KEY, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_IN_PROGRESS, IDEMPOTENCY_RACE,
        CART_NOT_FOUND, CART_REVISION_MISMATCH, CART_ITEM_MISSING, CART_SELECTION_MISMATCH,
        ITEM_UNAVAILABLE, COURSE_ALREADY_OWNED, COURSE_PENDING_PURCHASE, INSUFFICIENT_STOCK,
        VOUCHER_REJECTED, SHIPPING_QUOTE_REJECTED
    }

    private final Reason reason;
    public CheckoutRejectedException(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason);
    }
    public CheckoutRejectedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason);
    }
    public Reason reason() { return reason; }
}
