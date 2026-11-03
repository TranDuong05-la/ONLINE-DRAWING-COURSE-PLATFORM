package com.drawingcourse.backend.entity;

import java.math.BigInteger;
import java.util.Objects;

/** Server-side input from trusted catalog/voucher policies, never a request DTO. */
public record OrderLineSnapshot(OrderItemType itemType, Integer itemId, String name,
                                int quantity, long unitPriceVnd, boolean voucherEligible) {
    public OrderLineSnapshot {
        Objects.requireNonNull(itemType, "itemType");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || quantity < 1 || (itemType == OrderItemType.COURSE && quantity != 1)
                || unitPriceVnd < 0) {
            throw new IllegalArgumentException("Invalid order line snapshot");
        }
        BigInteger.valueOf(unitPriceVnd).multiply(BigInteger.valueOf(quantity)).longValueExact();
    }

    public long grossAmountVnd() {
        return Math.multiplyExact(unitPriceVnd, (long) quantity);
    }
}
