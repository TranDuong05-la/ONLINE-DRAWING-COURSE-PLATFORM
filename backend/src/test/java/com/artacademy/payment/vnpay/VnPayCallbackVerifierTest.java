package com.artacademy.payment.vnpay;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class VnPayCallbackVerifierTest {
    @Test void acceptsValidSignatureAndReturnsOnlyVerifiedSignedFields() {
        var fields = callbackFields();
        fields.put("vnp_SecureHash", signature(fields));
        var verified = new VnPayCallbackVerifier(properties()).verify(asMulti(fields));
        assertEquals("merchant-100", verified.merchantReference());
        assertEquals("1234500", verified.amountMinorUnits());
        assertFalse(verified.signedParameters().containsKey("vnp_SecureHash"));
    }

    @Test void rejectsModifiedAmountInvalidSignatureMissingRequiredAndDuplicateValues() {
        var signed = callbackFields();
        signed.put("vnp_SecureHash", signature(signed));
        var modified = new LinkedHashMap<>(signed); modified.put("vnp_Amount", "999");
        assertThrows(IllegalArgumentException.class, () -> new VnPayCallbackVerifier(properties()).verify(asMulti(modified)));
        var invalid = new LinkedHashMap<>(signed); invalid.put("vnp_SecureHash", "f".repeat(128));
        assertThrows(IllegalArgumentException.class, () -> new VnPayCallbackVerifier(properties()).verify(asMulti(invalid)));
        var missing = new LinkedHashMap<>(signed); missing.remove("vnp_TxnRef");
        assertThrows(IllegalArgumentException.class, () -> new VnPayCallbackVerifier(properties()).verify(asMulti(missing)));
        var duplicate = asMulti(signed); duplicate.put("vnp_Amount", List.of("1234500", "1234500"));
        assertThrows(IllegalArgumentException.class, () -> new VnPayCallbackVerifier(properties()).verify(duplicate));
    }

    private static LinkedHashMap<String, String> callbackFields() {
        var values = new LinkedHashMap<String, String>();
        values.put("vnp_TmnCode", "DEMOV210"); values.put("vnp_Amount", "1234500");
        values.put("vnp_TxnRef", "merchant-100"); values.put("vnp_ResponseCode", "00");
        values.put("vnp_TransactionStatus", "00"); values.put("vnp_OrderInfo", "Payment for order 71");
        values.put("vnp_BankCode", "NCB"); values.put("vnp_TransactionNo", "123456789");
        return values;
    }
    private static String signature(Map<String, String> values) {
        return VnPaySignature.hmacSha512Hex("unit-test-secret", VnPaySignature.canonical(new TreeMap<>(values)));
    }
    private static Map<String, List<String>> asMulti(Map<String, String> values) {
        var result = new LinkedHashMap<String, List<String>>(); values.forEach((key, value) -> result.put(key, List.of(value))); return result;
    }
    private static VnPayProperties properties() {
        var value = new VnPayProperties(); value.setEnabled(true); value.setTmnCode("DEMOV210");
        value.setSecretKey("unit-test-secret"); value.setReturnUrl("https://merchant.example/return");
        value.setIpnUrl("https://merchant.example/ipn"); return value;
    }
}
