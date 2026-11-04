package com.drawingcourse.backend.service.checkout;

/** Typed business rejection shared by the Checkout coordinator and its gated HTTP boundary. */
public final class CheckoutRejectedException extends RuntimeException {
    public enum Reason {
        INVALID_IDEMPOTENCY_KEY, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_IN_PROGRESS, IDEMPOTENCY_RACE,
        CART_NOT_FOUND, CART_REVISION_MISMATCH, CART_ITEM_MISSING, CART_SELECTION_MISMATCH,
        ITEM_UNAVAILABLE, UNSUPPORTED_ITEM_TYPE, COURSE_ALREADY_OWNED, COURSE_PENDING_PURCHASE, INSUFFICIENT_STOCK,
        VOUCHER_REJECTED, SHIPPING_QUOTE_REJECTED,
        /** A Combo covers a Course that is also selected on its own or inside another selected Combo. */
        COMBO_COURSE_OVERLAP
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
