package com.artacademy.supply.common;

import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ của phần E, có mã lỗi (vd. OUT_OF_STOCK) và HTTP status rõ ràng.
 * Trả về client theo định dạng ApiResponse chung (success=false, message).
 */
public class SupplyException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public SupplyException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static SupplyException notFound(String what) {
        return new SupplyException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " không tồn tại");
    }

    public static SupplyException badRequest(String code, String message) {
        return new SupplyException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static SupplyException conflict(String code, String message) {
        return new SupplyException(HttpStatus.CONFLICT, code, message);
    }

    public static SupplyException forbidden(String message) {
        return new SupplyException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
