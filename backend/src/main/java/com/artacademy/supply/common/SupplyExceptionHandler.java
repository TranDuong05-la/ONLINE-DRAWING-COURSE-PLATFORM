package com.artacademy.supply.common;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.artacademy.dto.ApiResponse;

/**
 * Xử lý riêng SupplyException, chạy trước GlobalExceptionHandler (vốn có handler Exception.class chung),
 * để không phải sửa file dùng chung của nhóm.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SupplyExceptionHandler {

    @ExceptionHandler(SupplyException.class)
    public ResponseEntity<ApiResponse<Void>> handle(SupplyException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.error(ex.getMessage()));
    }
}
