package com.artacademy.supply.inventory;

public enum InventoryTransactionType {
    /** Admin nhập hàng: onHand tăng. */
    IMPORT,
    /** Giữ hàng khi tạo đơn: reserved tăng. */
    RESERVE,
    /** Trả giữ khi đơn thất bại/hủy/hết hạn: reserved giảm. */
    RELEASE,
    /** Xuất kho khi chuyển hàng đi: onHand và reserved cùng giảm. */
    EXPORT,
    /** Hàng giao thất bại đã về kho: onHand tăng. */
    RETURN
}
