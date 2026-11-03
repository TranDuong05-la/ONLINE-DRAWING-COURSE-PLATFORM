package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Optional;

/** Client intent only. It deliberately has no user ID, price, Order ID, or Product fields. */
public record CourseOnlyCheckoutHttpRequest(
        @NotNull @PositiveOrZero Long cartRevision,
        @NotEmpty List<@Valid Selection> selections,
        String voucherCode) {

    public CourseOnlyCheckoutHttpRequest {
        if (selections != null && selections.stream().filter(java.util.Objects::nonNull)
                .map(Selection::cartItemId).filter(java.util.Objects::nonNull).distinct().count() != selections.size()) {
            throw new IllegalArgumentException("Duplicate Course Cart item selection");
        }
    }

    public CheckoutRequest toCheckoutRequest() {
        return new CheckoutRequest(cartRevision,
                selections.stream().map(s -> new CheckoutRequest.Selection(s.cartItemId(), 1)).toList(),
                Optional.ofNullable(voucherCode).map(String::strip).filter(value -> !value.isEmpty()),
                Optional.empty());
    }

    public record Selection(@NotNull @Positive Integer cartItemId) { }
}
