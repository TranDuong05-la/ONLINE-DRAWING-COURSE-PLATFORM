package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Optional;

/** Authenticated client intent only. Prices, user identity and provider evidence are never accepted. */
public record ProductCheckoutHttpRequest(@NotNull @PositiveOrZero Long cartRevision,
        @NotEmpty List<@Valid Selection> selections, String voucherCode, @Valid Address address) {
    public CheckoutRequest toCheckoutRequest() {
        return new CheckoutRequest(cartRevision,
                selections.stream().map(s -> new CheckoutRequest.Selection(s.cartItemId(), s.quantity())).toList(),
                Optional.ofNullable(voucherCode).map(String::strip).filter(s -> !s.isEmpty()),
                Optional.ofNullable(address).map(Address::toDomain));
    }
    public record Selection(@NotNull @Positive Integer cartItemId, @NotNull @Positive Integer quantity) { }
    public record Address(@NotNull String recipient, @NotNull String phone, @NotNull String addressLine,
            @NotNull String locality, String postalCode, @NotNull String countryCode) {
        CheckoutRequest.Address toDomain() {
            return new CheckoutRequest.Address(recipient, phone, addressLine,
                    locality, postalCode == null ? "" : postalCode, countryCode);
        }
    }
}
