package com.artacademy.supply.api;

/**
 * Một dòng hàng cần giữ.
 * gift = true: quà kèm khóa học (B cấu hình trong course_included_kits), giá 0 nhưng vẫn trừ kho như hàng mua.
 */
public record ReserveLine(Long productId, int quantity, boolean gift) {
    public static ReserveLine purchase(Long productId, int quantity) {
        return new ReserveLine(productId, quantity, false);
    }

    public static ReserveLine gift(Long productId, int quantity) {
        return new ReserveLine(productId, quantity, true);
    }
}
