package com.drawingcourse.backend.service.settlement;

import java.time.Instant;

/** Required D-owned transactional voucher participant; consume must join the settlement transaction. */
public interface VoucherConsumptionPort {
    void consumeForOrder(Integer userId, Integer orderId, String paymentReference,
                         Instant providerPaidAt, Instant observedAt);
}
