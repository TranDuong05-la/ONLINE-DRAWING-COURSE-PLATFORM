package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.repository.OrderRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Database-backed owner-scoped Course pending-purchase check. */
@Component
public final class JpaActivePendingCoursePurchaseProvider implements ActivePendingCoursePurchaseProvider {
    private final OrderRepository orders;

    public JpaActivePendingCoursePurchaseProvider(OrderRepository orders) {
        this.orders = Objects.requireNonNull(orders);
    }

    @Override
    public boolean hasActivePendingPurchase(Integer authenticatedUserId, Integer courseId, Instant observedAt) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new IllegalArgumentException("Authenticated user ID must be positive");
        }
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("Course ID must be positive");
        }
        Objects.requireNonNull(observedAt, "observedAt");
        return orders.existsActivePendingCoursePurchase(authenticatedUserId, courseId,
                OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT, observedAt);
    }
}
