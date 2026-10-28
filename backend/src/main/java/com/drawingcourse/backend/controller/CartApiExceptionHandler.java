package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.artacademy.exception.BusinessException;
import com.drawingcourse.backend.service.CartCatalogUnavailableException;
import java.util.NoSuchElementException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Cart-only error mapping; authentication errors remain handled by the existing security chain. */
@RestControllerAdvice(assignableTypes = CartController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CartApiExceptionHandler {
    @ExceptionHandler(CartInvalidRequestException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(CartInvalidRequestException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformedBody() {
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid Cart request body"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Cart item not found"));
    }

    @ExceptionHandler({CartConflictException.class, BusinessException.class, ArithmeticException.class})
    public ResponseEntity<ApiResponse<Void>> conflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Cart operation conflicts with current state"));
    }

    @ExceptionHandler(CartCatalogUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> providerUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("Cart catalog is temporarily unavailable"));
    }
}
