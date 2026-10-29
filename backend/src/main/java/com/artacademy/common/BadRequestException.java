package com.artacademy.common;

import org.springframework.http.HttpStatus;

/** 400 - dữ liệu gửi lên vi phạm quy tắc nghiệp vụ. */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
