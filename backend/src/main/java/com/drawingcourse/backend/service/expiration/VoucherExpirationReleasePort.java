package com.drawingcourse.backend.service.expiration;

import java.time.Instant;

/** Required D-owned local participant; releases the Order's reserved Voucher claim/counters in the caller transaction. */
public interface VoucherExpirationReleasePort {
    void releaseVoucherForExpiredOrder(Integer userId, Integer orderId, Instant releasedAt);
}
