package com.drawingcourse.backend.entity;

import java.util.Objects;

/** Immutable historical amounts; never updated when catalog prices or voucher rules change. */
public record OrderItem(OrderLineSnapshot catalogSnapshot, long grossAmountVnd,
                        long allocatedDiscountVnd, long netAmountVnd) {
    public OrderItem {
        Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
        if (grossAmountVnd != catalogSnapshot.grossAmountVnd() || allocatedDiscountVnd < 0
                || allocatedDiscountVnd > grossAmountVnd
                || (!catalogSnapshot.voucherEligible() && allocatedDiscountVnd != 0)
                || netAmountVnd != grossAmountVnd - allocatedDiscountVnd) {
            throw new IllegalArgumentException("Inconsistent item monetary snapshot");
        }
    }

    public static OrderItem withDiscount(OrderLineSnapshot snapshot, long discount) {
        return new OrderItem(snapshot, snapshot.grossAmountVnd(), discount,
                Math.subtractExact(snapshot.grossAmountVnd(), discount));
    }

    /**
     * Net allocation for a zero-based contiguous unit range. First (net % quantity)
     * units receive one extra VND. A future refund ledger must prevent range reuse
     * and require successful payment and eligibility; this method grants neither.
     */
    public long netAmountForUnits(int firstUnit, int unitCount) {
        int quantity = catalogSnapshot.quantity();
        if (firstUnit < 0 || unitCount < 1 || firstUnit > quantity - unitCount) {
            throw new IllegalArgumentException("Invalid unit range");
        }
        long base = netAmountVnd / quantity;
        long remainder = netAmountVnd % quantity;
        long extras = Math.min((long) firstUnit + unitCount, remainder) - Math.min(firstUnit, remainder);
        return Math.addExact(Math.multiplyExact(base, (long) unitCount), extras);
    }
}
