package com.drawingcourse.backend.service.checkout;

import com.drawingcourse.backend.service.ActivePendingCoursePurchaseProvider;

import java.time.Instant;

/** C-owned ownership and atomic purchase-claim boundary; every mutation must join Checkout's transaction. */
public interface CoursePurchasePort extends ActivePendingCoursePurchaseProvider {
    boolean isOwnedBy(Integer authenticatedUserId, Integer courseId);

    /** Must serialize competing claims for the same user/course, not rely only on a preceding read. */
    void claimForOrder(Integer authenticatedUserId, Integer courseId, Integer orderId,
                       Instant observedAt, Instant expiresAt);

    /** Future verified-settlement operation. */
    void confirmForOrder(Integer authenticatedUserId, Integer courseId, Integer orderId, Instant confirmedAt);

    /** Future final-unpaid cancellation/expiry operation. Retryable payment failures must not release. */
    void releaseForOrder(Integer authenticatedUserId, Integer courseId, Integer orderId,
                         String terminalReason, Instant releasedAt);
}
