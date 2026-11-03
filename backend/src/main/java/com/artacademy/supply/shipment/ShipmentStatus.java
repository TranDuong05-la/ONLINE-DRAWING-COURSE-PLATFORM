package com.artacademy.supply.shipment;

import java.util.EnumSet;
import java.util.Set;

/**
 * PREPARING -> SHIPPING -> DELIVERED
 *                      \-> FAILED -> SHIPPING (giao lại) | RETURNED (hàng đã về kho)
 * PREPARING -> CANCELLED (chỉ khi D hủy đơn trước lúc xuất kho)
 */
public enum ShipmentStatus {
    PREPARING,
    SHIPPING,
    DELIVERED,
    FAILED,
    RETURNED,
    CANCELLED;

    public Set<ShipmentStatus> nextByAdmin() {
        return switch (this) {
            case PREPARING -> EnumSet.of(SHIPPING);
            case SHIPPING -> EnumSet.of(DELIVERED, FAILED);
            case FAILED -> EnumSet.of(SHIPPING, RETURNED);
            default -> EnumSet.noneOf(ShipmentStatus.class);
        };
    }
}
