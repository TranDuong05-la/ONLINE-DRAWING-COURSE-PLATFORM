package com.drawingcourse.backend.service.expiration;

import java.time.Instant;

/** Required C-owned local participant; release only the specified Order's unconfirmed Course claim idempotently. */
public interface CoursePurchaseReleasePort {
    void releaseCourseForExpiredOrder(Integer userId, Integer courseId, Integer orderId, Instant releasedAt);
}
