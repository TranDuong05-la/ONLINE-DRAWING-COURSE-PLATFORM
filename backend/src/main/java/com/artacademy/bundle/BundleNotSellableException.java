package com.artacademy.bundle;

import com.artacademy.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Combo hiện không bán được (đã tắt, có khóa không còn xuất bản, hoặc thiếu quà tặng kèm), HTTP 409.
 * Mã lỗi ổn định: {@link #ERROR_CODE}. Lý do cụ thể ở {@link #getSellableStatus()}.
 */
public class BundleNotSellableException extends ApiException {

    public static final String ERROR_CODE = "COMBO_NOT_SELLABLE";

    private final BundleSellableStatus sellableStatus;

    public BundleNotSellableException(BundleSellableStatus sellableStatus, String reason) {
        super(HttpStatus.CONFLICT, reason);
        this.sellableStatus = sellableStatus;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    /** Lý do không bán được (INACTIVE, COURSE_NOT_PUBLISHED hoặc KIT_UNAVAILABLE). getStatus() của lớp cha là mã HTTP. */
    public BundleSellableStatus getSellableStatus() {
        return sellableStatus;
    }
}