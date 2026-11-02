package com.artacademy.payment.vnpay;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Verifies callback authenticity only. Callers must independently load and reconcile persisted payment data. */
public final class VnPayCallbackVerifier {
    private static final Set<String> REQUIRED = Set.of("vnp_TmnCode", "vnp_Amount", "vnp_BankCode",
            "vnp_OrderInfo", "vnp_TransactionNo", "vnp_ResponseCode", "vnp_TransactionStatus",
            "vnp_TxnRef", "vnp_SecureHash");
    private final VnPayProperties properties;

    public VnPayCallbackVerifier(VnPayProperties properties) { this.properties = Objects.requireNonNull(properties); }

    public VerifiedVnPayCallback verify(Map<String, ? extends List<String>> requestParameters) {
        properties.validateReady();
        Objects.requireNonNull(requestParameters, "requestParameters");
        for (String required : REQUIRED) {
            List<String> values = requestParameters.get(required);
            if (values == null || values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank())
                throw new IllegalArgumentException("Missing or duplicated required VNPay callback field: " + required);
        }
        Map<String, String> signed = new LinkedHashMap<>();
        requestParameters.forEach((key, values) -> {
            if (key == null || !key.startsWith("vnp_")) return;
            if (values == null || values.size() != 1 || values.getFirst() == null)
                throw new IllegalArgumentException("Duplicated VNPay callback parameter");
            if (!"vnp_SecureHash".equals(key) && !"vnp_SecureHashType".equals(key)) signed.put(key, values.getFirst());
        });
        if (!properties.getTmnCode().equals(signed.get("vnp_TmnCode")))
            throw new IllegalArgumentException("VNPay callback merchant does not match");
        if (!signed.get("vnp_Amount").matches("[0-9]{1,12}")
                || !signed.get("vnp_TxnRef").matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Invalid VNPay callback amount or reference format");
        String provided = requestParameters.get("vnp_SecureHash").getFirst();
        if (!provided.matches("(?i)[0-9a-f]{128}")) throw new IllegalArgumentException("Invalid VNPay signature format");
        String expected = VnPaySignature.hmacSha512Hex(properties.getSecretKey(), VnPaySignature.canonical(signed));
        if (!MessageDigest.isEqual(expected.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                provided.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("Invalid VNPay callback signature");
        return new VerifiedVnPayCallback(signed, provided);
    }
}
