package com.drawingcourse.backend.service.checkout;

import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.entity.VoucherClaim;
import com.drawingcourse.backend.service.StoredOrder;

import java.time.Instant;
import java.util.Optional;

/** D-owned voucher validation/reservation boundary; locks and counters must join the Checkout transaction. */
public interface VoucherReservationPort {
    /** Validates campaign, minimum and capacity and holds the definition/usage locks through transaction end. */
    Optional<Quote> quoteAndLock(Integer authenticatedUserId, String code, long eligibleSubtotalVnd,
                                 Instant observedAt);

    /** Persists claim snapshot/counters after an Order ID exists, in the same transaction. */
    void reserveForOrder(Integer authenticatedUserId, StoredOrder storedOrder, Order order,
                         Quote quote, Instant reservedAt);

    /** Release only after the persisted Order has reached a terminal unpaid state. */
    void releaseForOrder(Integer authenticatedUserId, Integer orderId,
                         VoucherClaim.ReleaseReason reason, Instant releasedAt);

    /** Call only after verified, timely settlement has moved the persisted Order to CONFIRMED/SUCCEEDED. */
    void consumeForOrder(Integer authenticatedUserId, Integer orderId, String paymentReference,
                         Instant verifiedPaidAt, Instant observedAt);

    record Quote(Integer voucherId, long definitionVersion, OrderVoucher terms) {
        public Quote {
            if (voucherId == null || voucherId <= 0 || definitionVersion < 0 || terms == null)
                throw new IllegalArgumentException("Invalid voucher quote");
        }
    }
}
