package com.artacademy.course.integration;

import com.artacademy.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Khóa học hiện không bán được (HTTP 409). Mã lỗi ổn định cho D và Flutter: {@link #ERROR_CODE}.
 * LƯU Ý: nhóm mới chốt hai mã của combo; mã COURSE_NOT_SELLABLE cho khóa lẻ là đề xuất của B, chờ D xác nhận.
 */
public class CourseNotSellableException extends ApiException {

    public static final String ERROR_CODE = "COURSE_NOT_SELLABLE";

    private final CourseSellableStatus sellableStatus;

    public CourseNotSellableException(CourseSellableStatus sellableStatus, String reason) {
        super(HttpStatus.CONFLICT, reason);
        this.sellableStatus = sellableStatus;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    /** Lý do không bán được (KIT_UNAVAILABLE). getStatus() của lớp cha là mã HTTP. */
    public CourseSellableStatus getSellableStatus() {
        return sellableStatus;
    }
}