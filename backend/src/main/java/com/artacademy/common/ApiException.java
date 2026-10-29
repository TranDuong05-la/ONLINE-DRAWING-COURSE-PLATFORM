package com.artacademy.common;

import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ có kèm mã HTTP. Ném ra ở service, GlobalExceptionHandler sẽ đổi thành JSON lỗi chuẩn.
 * Phần message viết tiếng Việt vì Flutter hiển thị thẳng cho người dùng.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
