package com.artacademy.payment.vnpay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class VnPaySignature {
    private VnPaySignature() { }

    static String canonical(Map<String, String> parameters) {
        return new TreeMap<>(parameters).entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    static String hmacSha512Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA512 is unavailable", ex);
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
