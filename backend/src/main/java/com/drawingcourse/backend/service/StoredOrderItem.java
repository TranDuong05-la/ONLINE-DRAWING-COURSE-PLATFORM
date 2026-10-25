package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.OrderItem;

public record StoredOrderItem(Integer id, Integer orderId, int linePosition, OrderItem snapshot) { }
