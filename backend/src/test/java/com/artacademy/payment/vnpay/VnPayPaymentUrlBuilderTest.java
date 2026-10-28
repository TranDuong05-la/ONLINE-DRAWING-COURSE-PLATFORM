package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.service.StoredOrder;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class VnPayPaymentUrlBuilderTest {
    private static final Instant CREATED = Instant.parse("2026-10-28T10:00:00Z");
    private static final Instant EXPIRY = CREATED.plusSeconds(1800);

    @Test void buildsDeterministicSignedUrlFromPersistedAmountAndAttempt() {
        var builder = new VnPayPaymentUrlBuilder(properties());
        var first = builder.build(storedOrder(), attempt(), "127.0.0.1", CREATED.plusSeconds(1));
        var second = builder.build(storedOrder(), attempt(), "127.0.0.1", CREATED.plusSeconds(1));
        assertEquals(first, second);
        assertTrue(first.startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"));
        var fields = parse(first);
        assertEquals("1234500", fields.get("vnp_Amount"));
        assertEquals("merchant-100", fields.get("vnp_TxnRef"));
        assertEquals("20261028170000", fields.get("vnp_CreateDate"));
        assertEquals("20261028173000", fields.get("vnp_ExpireDate"));
        assertEquals(VnPaySignature.hmacSha512Hex("unit-test-secret", VnPaySignature.canonical(withoutHash(fields))),
                fields.get("vnp_SecureHash"));
        assertEquals(fields.keySet().stream().sorted().toList(), new java.util.ArrayList<>(fields.keySet()));
    }

    @Test void rejectsExpiredAtExclusiveDeadlineAndInconsistentOrInvalidSnapshots() {
        var builder = new VnPayPaymentUrlBuilder(properties());
        assertThrows(IllegalStateException.class, () -> builder.build(storedOrder(), attempt(), "127.0.0.1", EXPIRY));
        assertThrows(IllegalArgumentException.class, () -> builder.build(storedOrder(), attempt(), "127.0.0.1\r\nX", CREATED.plusSeconds(1)));
        var wrongProvider = PaymentAttempt.create(71, order(), "other", "merchant-100", Clock.fixed(CREATED, ZoneOffset.UTC)).withIdentity(8);
        assertThrows(IllegalStateException.class, () -> builder.build(storedOrder(), wrongProvider, "127.0.0.1", CREATED.plusSeconds(1)));
        var disabled = properties(); disabled.setEnabled(false);
        assertThrows(IllegalStateException.class, () -> new VnPayPaymentUrlBuilder(disabled).build(storedOrder(), attempt(), "127.0.0.1", CREATED.plusSeconds(1)));
    }

    @Test void encodesUtf8ValuesAndSortsCanonicalParameters() {
        assertEquals("a=one+two&z=%C4%91", VnPaySignature.canonical(Map.of("z", "đ", "a", "one two")));
    }

    private static Order order() {
        return Order.create(19, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 3, "Màu nước", 1, 12_345, true)),
                0, Optional.empty(), Clock.fixed(CREATED, ZoneOffset.UTC));
    }
    private static StoredOrder storedOrder() {
        var order = order();
        return new StoredOrder(71, 1L, EXPIRY, CREATED, CREATED, order, List.of());
    }
    private static PaymentAttempt attempt() {
        return PaymentAttempt.create(71, order(), "vnpay", "merchant-100", Clock.fixed(CREATED, ZoneOffset.UTC)).withIdentity(8);
    }
    private static VnPayProperties properties() {
        var value = new VnPayProperties();
        value.setEnabled(true); value.setTmnCode("DEMOV210"); value.setSecretKey("unit-test-secret");
        value.setReturnUrl("https://merchant.example/return"); value.setIpnUrl("https://merchant.example/ipn");
        return value;
    }
    private static Map<String, String> withoutHash(Map<String, String> fields) {
        var result = new TreeMap<>(fields); result.remove("vnp_SecureHash"); return result;
    }
    private static Map<String, String> parse(String url) {
        var result = new TreeMap<String, String>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            var parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
