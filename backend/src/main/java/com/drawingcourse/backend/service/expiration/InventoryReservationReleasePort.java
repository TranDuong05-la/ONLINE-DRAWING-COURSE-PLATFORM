package com.drawingcourse.backend.service.expiration;

import java.time.Instant;

/** Required E-owned local participant; release only the specified Order's Inventory reservation idempotently. */
public interface InventoryReservationReleasePort {
    void releaseProductForExpiredOrder(Integer userId, Integer productId, Integer orderId, Instant releasedAt);
}
