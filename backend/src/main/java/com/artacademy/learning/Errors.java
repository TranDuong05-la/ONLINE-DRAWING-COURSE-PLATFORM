package com.artacademy.learning;

import org.springframework.http.HttpStatus;

import com.artacademy.common.ApiException;

/**
 * Lỗi nghiệp vụ của phần C. Dùng chung ApiException của nhóm (package common), GlobalExceptionHandler
 * đổi thành ApiResponse lỗi với đúng mã HTTP và message tiếng Việt.
 */
public final class Errors {
	private Errors() {
	}

	public static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND, message);
	}

	public static ApiException forbidden(String message) {
		return new ApiException(HttpStatus.FORBIDDEN, message);
	}

	public static ApiException badRequest(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, message);
	}

	public static ApiException conflict(String message) {
		return new ApiException(HttpStatus.CONFLICT, message);
	}

	public static ApiException tooManyRequests(String message) {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
	}
}
