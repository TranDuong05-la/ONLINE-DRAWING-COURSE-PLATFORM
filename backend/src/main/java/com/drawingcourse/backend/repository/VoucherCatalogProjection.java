package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.OrderVoucher;
import java.time.Instant;

/** Read-only catalog row, including only the authenticated user's aggregate usage. */
public interface VoucherCatalogProjection {
    String getCode();
    OrderVoucher.Kind getKind();
    long getValue();
    Long getPercentageCapVnd();
    boolean isActive();
    Instant getValidFrom();
    Instant getValidUntil();
    long getMinimumSubtotalVnd();
    Long getTotalUsageLimit();
    Long getPerUserUsageLimit();
    long getReservedCount();
    long getRedeemedCount();
    long getUserReservedCount();
    long getUserRedeemedCount();
}
