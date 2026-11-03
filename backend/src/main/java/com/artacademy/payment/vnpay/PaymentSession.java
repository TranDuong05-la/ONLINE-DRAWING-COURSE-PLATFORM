package com.artacademy.payment.vnpay;

import java.time.Instant;

/** Response data for a locally-created payment attempt; no claim is made that VNPay accepted it. */
public record PaymentSession(Integer orderId, Integer paymentAttemptId, String merchantReference,
                             String paymentUrl, Instant expiresAt, boolean reused) {
    /** Signed URLs are short-lived capabilities; keep them out of accidental log output. */
    @Override public String toString() {
        return "PaymentSession[orderId=" + orderId + ", paymentAttemptId=" + paymentAttemptId
                + ", merchantReference=" + merchantReference + ", paymentUrl=<redacted>, expiresAt="
                + expiresAt + ", reused=" + reused + "]";
    }
}
