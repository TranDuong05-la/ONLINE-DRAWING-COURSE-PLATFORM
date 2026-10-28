package com.drawingcourse.backend.service.checkout;

import java.time.Instant;

/** E-owned inventory boundary. Reservations must be atomic, Order-idempotent, and join the local transaction. */
public interface InventoryReservationPort {
    void reserveForOrder(Integer authenticatedUserId, Integer productId, int quantity,
                         Integer orderId, Instant expiresAt);

    /** Future timely-payment confirmation; never perform an external fulfillment call here. */
    void confirmForOrder(Integer authenticatedUserId, Integer productId, Integer orderId, Instant confirmedAt);

    /** Future final-unpaid cancellation/expiry release. */
    void releaseForOrder(Integer authenticatedUserId, Integer productId, Integer orderId,
                         String terminalReason, Instant releasedAt);
}
