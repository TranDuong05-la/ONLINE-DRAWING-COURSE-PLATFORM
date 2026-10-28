package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.PaymentStatus;
import com.drawingcourse.backend.entity.PaymentAttempt;
import com.drawingcourse.backend.service.StoredOrder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Pure URL builder. Its inputs must be freshly loaded persisted Order/attempt snapshots. */
public final class VnPayPaymentUrlBuilder {
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNPAY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(VN_ZONE);
    private final VnPayProperties properties;

    public VnPayPaymentUrlBuilder(VnPayProperties properties) { this.properties = Objects.requireNonNull(properties); }

    public String build(StoredOrder storedOrder, PaymentAttempt attempt, String clientIp, Instant now) {
        properties.validateReady();
        Objects.requireNonNull(storedOrder, "storedOrder");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(now, "now");
        var order = Objects.requireNonNull(storedOrder.snapshot(), "persisted order snapshot");
        if (storedOrder.id() == null || storedOrder.id() <= 0 || attempt.id() == null || attempt.id() <= 0
                || !storedOrder.id().equals(attempt.orderId()) || !order.getUserId().equals(attempt.userId()))
            throw new IllegalArgumentException("Persisted Order and payment attempt identities do not match");
        if (!"vnpay".equalsIgnoreCase(attempt.provider())
                || (attempt.status() != PaymentStatus.CREATED && attempt.status() != PaymentStatus.PENDING)
                || attempt.reviewReason() != PaymentAttempt.ReviewReason.NONE
                || order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getPaymentStatus() == PaymentStatus.SUCCEEDED
                || order.getPaymentStatus() == PaymentStatus.UNKNOWN)
            throw new IllegalStateException("Order or payment attempt is not payable");
        if (order.getTotalVnd() <= 0 || attempt.expectedAmountVnd() != order.getTotalVnd()
                || !"VND".equals(attempt.currency()) || !storedOrder.createdAt().equals(attempt.orderCreatedAt())
                || !storedOrder.paymentExpiresAt().equals(attempt.expiresAt())
                || !order.getCreatedAt().equals(attempt.orderCreatedAt())
                || !order.getExpiresAt().equals(attempt.expiresAt()))
            throw new IllegalArgumentException("Persisted payment snapshot does not match its Order");
        if (!now.isBefore(attempt.expiresAt())) throw new IllegalStateException("Order payment deadline has expired");
        if (now.isBefore(attempt.createdAt())) throw new IllegalArgumentException("Payment time precedes attempt creation");
        if (clientIp == null || clientIp.isBlank() || clientIp.length() > 45
                || clientIp.indexOf('\r') >= 0 || clientIp.indexOf('\n') >= 0 || clientIp.indexOf(',') >= 0)
            throw new IllegalArgumentException("Invalid client IP address");
        if (attempt.merchantReference().length() > 100
                || !attempt.merchantReference().matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Invalid merchant payment reference");
        long amount;
        try { amount = Math.multiplyExact(attempt.expectedAmountVnd(), 100L); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Payment amount exceeds VNPay range", ex); }
        if (amount > 999_999_999_999L) throw new IllegalArgumentException("Payment amount exceeds VNPay range");

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("vnp_Version", properties.getApiVersion());
        fields.put("vnp_Command", properties.getCommand());
        fields.put("vnp_TmnCode", properties.getTmnCode());
        fields.put("vnp_Amount", Long.toString(amount));
        fields.put("vnp_CurrCode", "VND");
        fields.put("vnp_TxnRef", attempt.merchantReference());
        fields.put("vnp_OrderInfo", "Payment for order " + storedOrder.id());
        fields.put("vnp_OrderType", "other");
        fields.put("vnp_Locale", "vn");
        fields.put("vnp_ReturnUrl", properties.getReturnUrl());
        fields.put("vnp_IpAddr", clientIp);
        fields.put("vnp_CreateDate", VNPAY_DATE.format(attempt.createdAt()));
        fields.put("vnp_ExpireDate", VNPAY_DATE.format(attempt.expiresAt()));

        var signed = new TreeMap<>(fields);
        String signature = VnPaySignature.hmacSha512Hex(properties.getSecretKey(), VnPaySignature.canonical(signed));
        signed.put("vnp_SecureHash", signature);
        String query = signed.entrySet().stream()
                .map(entry -> VnPaySignature.encode(entry.getKey()) + "=" + VnPaySignature.encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return properties.getPaymentUrl() + "?" + query;
    }
}
