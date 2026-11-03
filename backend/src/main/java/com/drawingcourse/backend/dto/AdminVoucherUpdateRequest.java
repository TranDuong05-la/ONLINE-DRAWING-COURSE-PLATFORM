package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.entity.OrderVoucher;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record AdminVoucherUpdateRequest(
        @NotNull @Positive Long expectedDefinitionVersion,
        @NotNull OrderVoucher.Kind discountType,
        @NotNull @Positive Long discountValue,
        @Positive Long maximumDiscountVnd,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil,
        @NotNull @PositiveOrZero Long minimumEligibleMerchandiseSpendVnd,
        @PositiveOrZero Long totalUsageLimit,
        @PositiveOrZero Long perUserUsageLimit,
        @JsonProperty("code") String forbiddenCode,
        @JsonProperty("active") Boolean forbiddenActive) {
}
