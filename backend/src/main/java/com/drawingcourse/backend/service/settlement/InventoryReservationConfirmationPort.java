package com.drawingcourse.backend.service.settlement;

import java.time.Instant;

/** Required E-owned local transaction participant; confirms an Order's inventory reservations idempotently. */
public interface InventoryReservationConfirmationPort {
    void confirmProductForOrder(Integer userId, Integer productId, Integer orderId, Instant confirmedAt);
}
