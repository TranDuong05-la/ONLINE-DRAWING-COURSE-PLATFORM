package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.drawingcourse.backend.dto.OrderDetail;
import com.drawingcourse.backend.dto.OrderSummary;
import com.drawingcourse.backend.dto.PageResponse;
import com.drawingcourse.backend.service.OrderHistoryQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Objects;

@RestController
@RequestMapping("/api/orders")
public final class OrderHistoryController {
    private final OrderHistoryQueryService orders;

    public OrderHistoryController(OrderHistoryQueryService orders) {
        this.orders = Objects.requireNonNull(orders);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<OrderSummary>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success("Orders retrieved successfully", orders.list(page, size)));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderDetail>> detail(@PathVariable Integer orderId) {
        return ResponseEntity.ok(ApiResponse.success("Order retrieved successfully", orders.detail(orderId)));
    }
}