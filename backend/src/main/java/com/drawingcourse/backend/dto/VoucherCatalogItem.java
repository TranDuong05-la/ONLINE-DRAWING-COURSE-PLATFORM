package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.entity.OrderVoucher;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.drawingcourse.backend.service.VoucherCatalogAvailability;
import java.time.Instant;

/** Public voucher terms. Persistence identity, versions, counters and claims are deliberately absent. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VoucherCatalogItem(
        String code,
        OrderVoucher.Kind discountType,
        long discountValue,
        Long maximumDiscountVnd,
        long minimumEligibleMerchandiseSpendVnd,
        Instant validFrom,
        Instant validUntil,
        VoucherCatalogAvailability availability,
        boolean minimumSpendEvaluated) {
}
