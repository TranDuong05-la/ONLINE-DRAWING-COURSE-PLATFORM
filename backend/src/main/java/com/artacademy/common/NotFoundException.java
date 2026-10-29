package com.artacademy.common;

import org.springframework.http.HttpStatus;

/** 404 - không tìm thấy dữ liệu (hoặc dữ liệu không được phép lộ ra, ví dụ khóa nháp ở API public). */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
