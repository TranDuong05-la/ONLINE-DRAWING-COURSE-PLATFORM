package com.artacademy.service;

import java.math.BigDecimal;

/**
 * Số liệu khóa học và đơn hàng cho dashboard admin. Tách riêng để AuthService (tài khoản) không phụ thuộc trực tiếp
 * vào bảng khóa học và đơn hàng của phần khác.
 */
public interface AdminCatalogMetrics {

    /** Tổng số khóa học (mọi trạng thái). */
    long totalCourses();

    /** Tổng số đơn hàng (mọi trạng thái). */
    long totalOrders();

    /** Doanh thu = tổng tiền các đơn đã thanh toán thành công (CONFIRMED, COMPLETED), VND. */
    BigDecimal totalRevenue();
}
