package com.artacademy.service;

import com.artacademy.course.CourseRepository;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.repository.OrderMetricsRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đọc số liệu thật từ bảng khóa học (B) và đơn hàng (D); chỉ đọc tổng hợp, không tải từng đơn. */
@Service
@Transactional(readOnly = true)
public class AdminCatalogMetricsService implements AdminCatalogMetrics {

    /** Đơn đã được thanh toán thành công mới tính vào doanh thu. */
    private static final List<OrderStatus> PAID = List.of(OrderStatus.CONFIRMED, OrderStatus.COMPLETED);

    private final CourseRepository courses;
    private final OrderMetricsRepository orders;

    public AdminCatalogMetricsService(CourseRepository courses, OrderMetricsRepository orders) {
        this.courses = courses;
        this.orders = orders;
    }

    @Override
    public long totalCourses() {
        return courses.count();
    }

    @Override
    public long totalOrders() {
        return orders.countAllOrders();
    }

    @Override
    public BigDecimal totalRevenue() {
        return BigDecimal.valueOf(orders.sumTotalVndByStatusIn(PAID));
    }
}
