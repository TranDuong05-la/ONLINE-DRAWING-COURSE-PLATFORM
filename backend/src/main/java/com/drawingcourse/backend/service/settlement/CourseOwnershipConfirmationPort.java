package com.drawingcourse.backend.service.settlement;

import java.time.Instant;

/** Required C-owned local transaction participant; confirms an Order's course claims idempotently. */
public interface CourseOwnershipConfirmationPort {
    void confirmCourseForOrder(Integer userId, Integer courseId, Integer orderId, Instant confirmedAt);
}
