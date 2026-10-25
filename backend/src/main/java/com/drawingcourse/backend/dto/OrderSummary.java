package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.PaymentStatus;
import java.time.Instant;

/** Lightweight Order History row; item details are not loaded for page results. */
public record OrderSummary(Integer orderId, OrderStatus status, PaymentStatus paymentStatus,
                           Instant createdAt, Instant updatedAt, Instant paymentExpiresAt,
                           long totalVnd, String currency, long itemCount) { }