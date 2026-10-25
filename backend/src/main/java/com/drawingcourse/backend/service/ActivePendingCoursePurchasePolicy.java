package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.OrderStatus;
import java.time.Instant;
import java.util.Objects;

/** Pure policy for the future owner-scoped Order-item existence query. */
public final class ActivePendingCoursePurchasePolicy {
    private ActivePendingCoursePurchasePolicy() { }

    /**
     * A pending Order blocks only before its immutable, exclusive payment deadline.
     * At the exact deadline it is no longer payable, even if an expiration worker has not
     * yet persisted EXPIRED. Terminal cancelled/expired Orders never block. Payment-attempt
     * status is deliberately not used: FAILED retries remain payable and UNKNOWN/successful
     * evidence must remain blocking until Order reconciliation or its deadline.
     */
    public static boolean isActive(OrderStatus status, Instant paymentExpiresAt, Instant observedAt) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
        return status == OrderStatus.PENDING_PAYMENT
                && paymentExpiresAt != null
                && observedAt.isBefore(paymentExpiresAt);
    }
}
