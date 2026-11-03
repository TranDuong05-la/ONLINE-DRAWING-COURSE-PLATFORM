package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.entity.OrderVoucher;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminVoucherResponse(
        Integer id,
        String code,
        OrderVoucher.Kind discountType,
        long discountValue,
        Long maximumDiscountVnd,
        boolean active,
        Instant validFrom,
        Instant validUntil,
        long minimumEligibleMerchandiseSpendVnd,
        Long totalUsageLimit,
        Long perUserUsageLimit,
        long reservedCount,
        long redeemedCount,
        long definitionVersion,
        Instant createdAt,
        Instant updatedAt,
        boolean hasClaims,
        boolean financialTermsEditable) {
}
