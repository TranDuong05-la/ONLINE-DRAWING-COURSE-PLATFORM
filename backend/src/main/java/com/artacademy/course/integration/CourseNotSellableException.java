package com.artacademy.course.integration;

import com.artacademy.common.ApiException;
import org.springframework.http.HttpStatus;

public class CourseNotSellableException extends ApiException {
	public static final String ERROR_CODE = "COURSE_NOT_SELLABLE";
	private final CourseSellableStatus sellableStatus;
	public CourseNotSellableException(CourseSellableStatus status, String reason) { super(HttpStatus.CONFLICT, reason); this.sellableStatus = status; }
	public String getErrorCode() { return ERROR_CODE; }
	public CourseSellableStatus getSellableStatus() { return sellableStatus; }
}
