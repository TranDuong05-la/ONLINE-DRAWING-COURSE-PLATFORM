package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.drawingcourse.backend.dto.ProductCheckoutHttpRequest;
import com.drawingcourse.backend.service.checkout.ProductCheckoutApplicationService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Separate default-off HTTP boundary for Product and mixed Course/Product Checkout. */
@RestController
@RequestMapping("/api/checkout")
@ConditionalOnProperty(name = "app.checkout.product.http-enabled", havingValue = "true")
@ConditionalOnBean(ProductCheckoutApplicationService.class)
public final class ProductCheckoutController {
    private final ProductCheckoutApplicationService checkout;
    public ProductCheckoutController(ProductCheckoutApplicationService checkout) { this.checkout = checkout; }
    @PostMapping
    public ResponseEntity<ApiResponse<CheckoutResult>> checkout(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ProductCheckoutHttpRequest request) {
        var result = checkout.checkout(key, request.toCheckoutRequest());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.success(result.replayed() ? "Checkout replayed" : "Order created",
                        new CheckoutResult(result.orderId(), result.replayed())));
    }
    public record CheckoutResult(Integer orderId, boolean replayed) { }
}
