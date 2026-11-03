package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.drawingcourse.backend.service.checkout.CheckoutRejectedException;
import com.drawingcourse.backend.service.checkout.CourseCatalogPort;
import com.drawingcourse.backend.service.checkout.CoursePurchasePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CourseOnlyCheckoutController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.checkout.course-only.enabled", havingValue = "true")
@ConditionalOnBean({CourseCatalogPort.class, CoursePurchasePort.class})
public final class CourseOnlyCheckoutExceptionHandler {
    @ExceptionHandler(CheckoutRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(CheckoutRejectedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidRequest(Exception ignored) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid Course Checkout request"));
    }
}
