package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.entity.OrderVoucher;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record AdminVoucherCreateRequest(
        @NotBlank @Size(max = 255) String code,
        @NotNull OrderVoucher.Kind discountType,
        @NotNull @Positive Long discountValue,
        @Positive Long maximumDiscountVnd,
        @NotNull Boolean active,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil,
        @NotNull @PositiveOrZero Long minimumEligibleMerchandiseSpendVnd,
        @PositiveOrZero Long totalUsageLimit,
        @PositiveOrZero Long perUserUsageLimit) {
}
