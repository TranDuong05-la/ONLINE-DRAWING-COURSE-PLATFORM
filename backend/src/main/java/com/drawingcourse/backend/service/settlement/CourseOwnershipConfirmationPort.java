package com.drawingcourse.backend.service.settlement;

import java.time.Instant;

/** Required C-owned local transaction participant; confirms an Order's course claims idempotently. */
public interface CourseOwnershipConfirmationPort {
    /** Confirms the exact persisted OrderItem claim and its resulting Enrollment provenance. */
    void confirmCourseForOrder(Integer userId, Integer courseId, Integer orderId,
                               Integer orderItemId, Instant confirmedAt);
}
