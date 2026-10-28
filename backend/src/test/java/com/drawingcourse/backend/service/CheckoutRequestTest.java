package com.drawingcourse.backend.service;

import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutRequestTest {
    private CheckoutRequest.Address address(String recipient, String phone, String line,
            String locality, String postal, String country) {
        return new CheckoutRequest.Address(recipient, phone, line, locality, postal, country);
    }

    private CheckoutRequest request(long revision, List<CheckoutRequest.Selection> selections,
            Optional<String> voucher, Optional<CheckoutRequest.Address> address) {
        return new CheckoutRequest(revision, selections, voucher, address);
    }

    @Test void fingerprintIsStableAndSelectionOrderAndSurroundingWhitespaceAreCanonicalized() {
        var first = request(1L, List.of(new CheckoutRequest.Selection(20, 2), new CheckoutRequest.Selection(10, 1)),
                Optional.of(" SAVE10 "), Optional.of(address(" Recipient ", " 0900 ", " Street ", " City ", " 70000 ", " VN ")));
        var reordered = request(1L, List.of(new CheckoutRequest.Selection(10, 1), new CheckoutRequest.Selection(20, 2)),
                Optional.of("SAVE10"), Optional.of(address("Recipient", "0900", "Street", "City", "70000", "VN")));

        assertEquals(first.selections(), reordered.selections());
        assertEquals(first.canonicalFingerprint(), reordered.canonicalFingerprint());
        assertTrue(first.canonicalFingerprint().matches("v1:[a-f0-9]{64}"));
        assertEquals(first.canonicalFingerprint(), first.canonicalFingerprint());
    }

    @Test void fingerprintCoversRevisionSelectionsVoucherAndEveryAddressField() {
        var base = request(1L, List.of(new CheckoutRequest.Selection(10, 1)),
                Optional.of("SAVE"), Optional.of(address("R", "P", "L", "City", "Zip", "VN")));
        var variants = List.of(
                request(2L, base.selections(), base.voucherCode(), base.address()),
                request(1L, List.of(new CheckoutRequest.Selection(11, 1)), base.voucherCode(), base.address()),
                request(1L, List.of(new CheckoutRequest.Selection(10, 2)), base.voucherCode(), base.address()),
                request(1L, base.selections(), Optional.of("OTHER"), base.address()),
                request(1L, base.selections(), Optional.empty(), base.address()),
                request(1L, base.selections(), base.voucherCode(), Optional.empty()),
                request(1L, base.selections(), base.voucherCode(), Optional.of(address("R2", "P", "L", "City", "Zip", "VN"))),
                request(1L, base.selections(), base.voucherCode(), Optional.of(address("R", "P2", "L", "City", "Zip", "VN"))),
                request(1L, base.selections(), base.voucherCode(), Optional.of(address("R", "P", "L2", "City", "Zip", "VN"))),
                request(1L, base.selections(), base.voucherCode(), Optional.of(address("R", "P", "L", "City2", "Zip", "VN"))),
                request(1L, base.selections(), base.voucherCode(), Optional.of(address("R", "P", "L", "City", "Zip2", "VN"))),
                request(1L, base.selections(), base.voucherCode(), Optional.of(address("R", "P", "L", "City", "Zip", "US"))));

        assertTrue(variants.stream().allMatch(value -> !base.canonicalFingerprint().equals(value.canonicalFingerprint())));
    }

    @Test void voucherCodeFingerprintPreservesCaseForExactTechnicalIdentifierSemantics() {
        var upper = request(0L, List.of(new CheckoutRequest.Selection(1, 1)), Optional.of("SAVE"), Optional.empty());
        var lower = request(0L, List.of(new CheckoutRequest.Selection(1, 1)), Optional.of("save"), Optional.empty());
        assertNotEquals(upper.canonicalFingerprint(), lower.canonicalFingerprint());
    }

    @Test void requestRejectsInvalidAndAmbiguousSelections() {
        assertThrows(IllegalArgumentException.class, () -> request(0L, List.of(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> request(0L, List.of(new CheckoutRequest.Selection(0, 1)), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> request(0L, List.of(new CheckoutRequest.Selection(1, 0)), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> request(0L, List.of(
                new CheckoutRequest.Selection(1, 1), new CheckoutRequest.Selection(1, 2)), Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> request(0L, null, Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> request(0L, List.of(new CheckoutRequest.Selection(1, 1)), null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> request(0L, List.of(new CheckoutRequest.Selection(1, 1)), Optional.empty(), null));
        assertThrows(IllegalArgumentException.class, () -> request(-1L, List.of(new CheckoutRequest.Selection(1, 1)), Optional.empty(), Optional.empty()));
    }
}
