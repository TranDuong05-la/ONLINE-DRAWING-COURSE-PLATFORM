package com.artacademy.bundle;

import com.artacademy.common.ApiException;
import org.springframework.http.HttpStatus;

/** Combo hiện không bán được (đã tắt, hoặc có khóa không còn xuất bản). */
public class BundleNotSellableException extends ApiException {

    private final BundleSellableStatus sellableStatus;

    public BundleNotSellableException(BundleSellableStatus sellableStatus, String reason) {
        super(HttpStatus.CONFLICT, reason);
        this.sellableStatus = sellableStatus;
    }

    /** Lý do không bán được (INACTIVE hoặc COURSE_NOT_PUBLISHED). getStatus() của lớp cha là mã HTTP. */
    public BundleSellableStatus getSellableStatus() {
        return sellableStatus;
    }
}
