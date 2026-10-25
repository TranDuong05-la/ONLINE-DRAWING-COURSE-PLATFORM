package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.PaymentStatus;
import java.time.Instant;

/** Interface projection backed by a single owner-filtered page query. */
public interface OrderHistoryProjection {
    Integer getOrderId();
    OrderStatus getStatus();
    PaymentStatus getPaymentStatus();
    Instant getCreatedAt();
    Instant getUpdatedAt();
    Instant getPaymentExpiresAt();
    long getTotalVnd();
    String getCurrency();
    Long getItemCount();
}