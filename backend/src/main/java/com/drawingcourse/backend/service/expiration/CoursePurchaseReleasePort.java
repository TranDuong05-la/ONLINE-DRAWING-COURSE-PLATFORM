package com.drawingcourse.backend.service.expiration;

import java.time.Instant;

/** Required C-owned local participant; release only the specified Order's unconfirmed Course claim idempotently. */
public interface CoursePurchaseReleasePort {
    void releaseCourseForExpiredOrder(Integer userId, Integer courseId, Integer orderId, Instant releasedAt);

    /** Releases every unconfirmed claim of a Combo OrderItem idempotently; participants that cannot must fail closed. */
    default void releaseComboForExpiredOrder(Integer userId, Integer orderId, Integer orderItemId, Instant releasedAt) {
        throw new IllegalStateException("Combo expiration is not supported by this participant");
    }
}
