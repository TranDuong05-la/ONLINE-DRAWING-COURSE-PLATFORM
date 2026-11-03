package com.artacademy.bundle;

import com.artacademy.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Combo đã bị admin sửa (definitionVersion khác phiên bản D đang giữ), HTTP 409.
 * Mã lỗi ổn định: {@link #ERROR_CODE}. Kèm expectedVersion và currentVersion để Flutter biết cần tải lại combo.
 */
public class BundleChangedException extends ApiException {

    public static final String ERROR_CODE = "COMBO_DEFINITION_CHANGED";

    private final int expectedVersion;
    private final int currentVersion;

    public BundleChangedException(int expectedVersion, int currentVersion) {
        super(HttpStatus.CONFLICT, "Combo đã thay đổi, vui lòng xem lại trước khi thanh toán");
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public int getExpectedVersion() {
        return expectedVersion;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }
}