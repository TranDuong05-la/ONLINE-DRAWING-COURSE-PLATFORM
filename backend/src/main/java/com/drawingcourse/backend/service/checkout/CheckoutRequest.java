package com.drawingcourse.backend.service.checkout;

import java.util.*;

/** Intent only: no prices or user ID. Revision identifies the accepted cart contents. */
public record CheckoutRequest(String cartRevision, List<Selection> selections,
                              Optional<String> voucherCode, Optional<Address> address) {
    public CheckoutRequest {
        cartRevision = text(cartRevision);
        selections = selections.stream().sorted(Comparator.comparing(Selection::cartItemId)).toList();
        if (selections.isEmpty() || selections.stream().map(Selection::cartItemId).distinct().count() != selections.size()) {
            throw new IllegalArgumentException("Empty or duplicate checkout selections");
        }
        voucherCode = Objects.requireNonNull(voucherCode).map(CheckoutRequest::text);
        address = Objects.requireNonNull(address);
    }
    public record Selection(Integer cartItemId, int quantity) {
        public Selection {
            Objects.requireNonNull(cartItemId);
            if (quantity < 1) throw new IllegalArgumentException("Quantity must be positive");
        }
    }
    public record Address(String recipient, String phone, String addressLine, String locality,
                          String postalCode, String countryCode) {
        public Address {
            recipient = text(recipient); phone = text(phone); addressLine = text(addressLine);
            locality = text(locality); postalCode = Objects.requireNonNull(postalCode).strip();
            countryCode = text(countryCode);
        }
    }
    private static String text(String value) {
        String result = Objects.requireNonNull(value).strip();
        if (result.isEmpty()) throw new IllegalArgumentException("Blank checkout field");
        return result;
    }
}
