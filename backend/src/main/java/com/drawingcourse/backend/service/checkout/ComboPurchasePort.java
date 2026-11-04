package com.drawingcourse.backend.service.checkout;

import java.time.Instant;
import java.util.List;

/**
 * C-owned atomic purchase-claim boundary for a Combo's Courses. Every mutation must join Checkout's transaction.
 * The claim rows (one per Course, tagged with the Combo's OrderItem and position) are the immutable snapshot of what
 * the Combo granted at purchase time: settlement, expiration and refund use them, never the current Combo definition.
 */
public interface ComboPurchasePort {
    /**
     * Reserves every component Course for the Combo OrderItem, or none (the caller's transaction rolls back).
     * Must serialize competing claims for the same user/course, not rely only on a preceding read.
     */
    void claimComboForOrder(Integer authenticatedUserId, Integer orderId, Integer orderItemId,
                            List<ComboCatalogPort.Component> components, Instant observedAt, Instant expiresAt);
}
