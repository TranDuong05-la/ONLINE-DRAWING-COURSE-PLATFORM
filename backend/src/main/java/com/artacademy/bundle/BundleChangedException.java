package com.artacademy.bundle;

import com.artacademy.common.ApiException;
import org.springframework.http.HttpStatus;

/** Combo đã bị admin sửa (definitionVersion khác với phiên bản D đang giữ): D cần lấy snapshot mới và cho người dùng xem lại. */
public class BundleChangedException extends ApiException {

    private final int expectedVersion;
    private final int actualVersion;

    public BundleChangedException(int expectedVersion, int actualVersion) {
        super(HttpStatus.CONFLICT, "Combo đã thay đổi, vui lòng xem lại trước khi thanh toán");
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public int getExpectedVersion() {
        return expectedVersion;
    }

    public int getActualVersion() {
        return actualVersion;
    }
}
