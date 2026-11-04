package com.drawingcourse.backend.service.settlement;

import java.time.Instant;

/** Required C-owned local transaction participant; confirms an Order's course claims idempotently. */
public interface CourseOwnershipConfirmationPort {
    /** Confirms the exact persisted OrderItem claim and its resulting Enrollment provenance. */
    void confirmCourseForOrder(Integer userId, Integer courseId, Integer orderId,
                               Integer orderItemId, Instant confirmedAt);

    /**
     * Confirms EVERY persisted claim of a Combo OrderItem (all-or-nothing in the caller's transaction) and grants the
     * resulting Enrollments. The claim set recorded at Checkout is the snapshot; the current Combo definition is not read.
     * Participants that cannot do this must fail closed (the default), never confirm a partial Combo.
     */
    default void confirmComboForOrder(Integer userId, Integer orderId, Integer orderItemId, Instant confirmedAt) {
        throw new IllegalStateException("Combo settlement is not supported by this participant");
    }
}
