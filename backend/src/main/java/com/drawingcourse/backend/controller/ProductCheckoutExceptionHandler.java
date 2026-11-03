package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.drawingcourse.backend.service.checkout.CheckoutRejectedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ProductCheckoutController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ProductCheckoutExceptionHandler {
    @ExceptionHandler(CheckoutRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(CheckoutRejectedException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(failure.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid Checkout request"));
    }
}
