package com.drawingcourse.backend.service;

import java.time.Instant;

/**
 * Read-only, owner-scoped Order query. Active means PENDING_PAYMENT with immutable
 * paymentExpiresAt strictly after observedAt; cancelled/expired Orders do not block.
 * Retryable, unknown, and evidenced-success payment statuses remain blockers until
 * the Order state is terminal or its exclusive deadline has elapsed.
 */
@FunctionalInterface
public interface ActivePendingCoursePurchaseProvider {
    boolean hasActivePendingPurchase(Integer authenticatedUserId, Integer courseId, Instant observedAt);
}
