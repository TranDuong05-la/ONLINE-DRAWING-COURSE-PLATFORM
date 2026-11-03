package com.artacademy.supply.inventory;

public enum ReservationStatus {
    /** Đang giữ hàng cho đơn. */
    RESERVED,
    /** Đã trả giữ (đơn thất bại/hủy/hết hạn). */
    RELEASED,
    /** Đã xuất kho cùng chuyến giao. */
    EXPORTED
}
