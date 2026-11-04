package com.artacademy.payment.vnpay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Verifies the distinct pipe-delimited signature used by VNPay's QueryDR response. */
final class VnPayQueryDrVerifier {
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final List<String> SIGNED_FIELDS = List.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode",
            "vnp_Message", "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate",
            "vnp_TransactionNo", "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo",
            "vnp_PromotionCode", "vnp_PromotionAmount");
    private final VnPayProperties properties;

    VnPayQueryDrVerifier(VnPayProperties properties) { this.properties = Objects.requireNonNull(properties); }

    VerifiedResponse verify(Map<String, String> response, String expectedReference, String expectedOrderInfo) {
        Objects.requireNonNull(response);
        for (String key : List.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message",
                "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_TransactionNo",
                "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo", "vnp_SecureHash")) {
            if (response.get(key) == null || response.get(key).isBlank())
                throw new IllegalArgumentException("Incomplete signed VNPay QueryDR response");
        }
        String signature = response.get("vnp_SecureHash");
        if (!signature.matches("(?i)[0-9a-f]{128}")) throw new IllegalArgumentException("Invalid QueryDR signature format");
        String expectedHash = VnPaySignature.hmacSha512Hex(properties.getSecretKey(), responseCanonical(response));
        if (!MessageDigest.isEqual(expectedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("Invalid VNPay QueryDR signature");
        if (!"querydr".equals(response.get("vnp_Command"))
                || !properties.getTmnCode().equals(response.get("vnp_TmnCode"))
                || !expectedReference.equals(response.get("vnp_TxnRef"))
                || !expectedOrderInfo.equals(response.get("vnp_OrderInfo"))
                || !response.get("vnp_Amount").matches("[0-9]{1,12}"))
            throw new IllegalArgumentException("VNPay QueryDR response does not match the persisted attempt");

        boolean apiSuccess = "00".equals(response.get("vnp_ResponseCode"));
        boolean paymentSuccess = apiSuccess && "00".equals(response.get("vnp_TransactionStatus"))
                && "01".equals(response.get("vnp_TransactionType"));
        if (!paymentSuccess) return new VerifiedResponse(null, false, response.get("vnp_ResponseCode"));
        if (!response.get("vnp_TransactionNo").matches("[0-9]{1,15}")
                || response.get("vnp_PayDate") == null || response.get("vnp_PayDate").isBlank())
            throw new IllegalArgumentException("VNPay QueryDR success lacks transaction evidence");
        try { LocalDateTime.parse(response.get("vnp_PayDate"), PAY_DATE); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("VNPay QueryDR payment time is invalid", ex); }

        Map<String, String> normalized = new LinkedHashMap<>();
        normalized.put("vnp_TmnCode", response.get("vnp_TmnCode"));
        normalized.put("vnp_Amount", response.get("vnp_Amount"));
        normalized.put("vnp_BankCode", response.get("vnp_BankCode"));
        normalized.put("vnp_OrderInfo", response.get("vnp_OrderInfo"));
        normalized.put("vnp_TransactionNo", response.get("vnp_TransactionNo"));
        normalized.put("vnp_ResponseCode", "00");
        normalized.put("vnp_TransactionStatus", "00");
        normalized.put("vnp_TxnRef", response.get("vnp_TxnRef"));
        normalized.put("vnp_PayDate", response.get("vnp_PayDate"));
        return new VerifiedResponse(new VerifiedVnPayCallback(normalized, signature,
                VerifiedVnPayCallback.Source.QUERY_DR), true, "00");
    }

    static String responseCanonical(Map<String, String> response) {
        return SIGNED_FIELDS.stream().map(key -> response.getOrDefault(key, ""))
                .collect(java.util.stream.Collectors.joining("|"));
    }

    record VerifiedResponse(VerifiedVnPayCallback callback, boolean paymentSuccess, String apiResponseCode) { }
}
