package com.drawingcourse.backend.service.checkout;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Intent only: no prices or user ID. Revision identifies the accepted cart contents. */
public record CheckoutRequest(long cartRevision, List<Selection> selections,
                              Optional<String> voucherCode, Optional<Address> address) {
    public CheckoutRequest {
        if (cartRevision < 0) throw new IllegalArgumentException("Cart revision must be non-negative");
        Objects.requireNonNull(selections, "selections");
        var canonicalSelections = new ArrayList<Selection>(selections.size());
        for (Selection selection : selections) canonicalSelections.add(Objects.requireNonNull(selection, "selection"));
        canonicalSelections.sort(Comparator.comparing(Selection::cartItemId));
        selections = List.copyOf(canonicalSelections);
        if (selections.isEmpty() || selections.stream().map(Selection::cartItemId).distinct().count() != selections.size()) {
            throw new IllegalArgumentException("Empty or duplicate checkout selections");
        }
        voucherCode = Objects.requireNonNull(voucherCode).map(CheckoutRequest::text);
        address = Objects.requireNonNull(address, "address");
    }
    public record Selection(Integer cartItemId, int quantity) {
        public Selection {
            Objects.requireNonNull(cartItemId);
            if (cartItemId <= 0 || quantity < 1) throw new IllegalArgumentException("Cart item ID and quantity must be positive");
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

    /** Stable, versioned request identity. It contains request intent only, never prices or totals. */
    public String canonicalFingerprint() {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                write(out, "drawing-course-checkout-request-v1");
                out.writeLong(cartRevision);
                out.writeInt(selections.size());
                for (Selection selection : selections) {
                    out.writeInt(selection.cartItemId());
                    out.writeInt(selection.quantity());
                }
                out.writeBoolean(voucherCode.isPresent());
                if (voucherCode.isPresent()) write(out, voucherCode.orElseThrow());
                out.writeBoolean(address.isPresent());
                if (address.isPresent()) {
                    Address value = address.orElseThrow();
                    write(out, value.recipient());
                    write(out, value.phone());
                    write(out, value.addressLine());
                    write(out, value.locality());
                    write(out, value.postalCode());
                    write(out, value.countryCode());
                }
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            return "v1:" + HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Unable to fingerprint Checkout request", impossible);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }
}
