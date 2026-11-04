package com.artacademy.payment.vnpay;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VnPayQueryDrVerifierTest {
    private static final String SECRET = "synthetic-querydr-secret";

    @Test
    void signedQuerySuccessMapsOnlyProviderFactsToVerifiedReconciliationEvidence() {
        VnPayProperties properties = properties();
        Map<String, String> response = signedResponse("00");
        var verified = new VnPayQueryDrVerifier(properties).verify(response, "dca-attempt-1", "Payment for order 4");
        assertThat(verified.paymentSuccess()).isTrue();
        assertThat(verified.callback().source()).isEqualTo(VerifiedVnPayCallback.Source.QUERY_DR);
        assertThat(verified.callback().merchantReference()).isEqualTo("dca-attempt-1");
        assertThat(verified.callback().amountMinorUnits()).isEqualTo("1234500");
        assertThat(verified.callback().providerPaymentDate()).isEqualTo("20261104120000");
    }

    @Test
    void rejectsAlteredSignatureAndMismatchedPersistedAttemptIdentity() {
        VnPayQueryDrVerifier verifier = new VnPayQueryDrVerifier(properties());
        Map<String, String> altered = signedResponse("00");
        altered.put("vnp_Amount", "1");
        assertThatThrownBy(() -> verifier.verify(altered, "dca-attempt-1", "Payment for order 4"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("signature");
        Map<String, String> valid = signedResponse("00");
        assertThatThrownBy(() -> verifier.verify(valid, "other-attempt", "Payment for order 4"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
        assertThatThrownBy(() -> verifier.verify(valid, "dca-attempt-1", "Payment for order 5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
    }

    @Test
    void signedUnsuccessfulOrNonPaymentResponseNeverProducesSettlementEvidence() {
        Map<String, String> response = signedResponse("01");
        response.put("vnp_TransactionStatus", "01");
        sign(response);
        var verified = new VnPayQueryDrVerifier(properties()).verify(response, "dca-attempt-1", "Payment for order 4");
        assertThat(verified.paymentSuccess()).isFalse();
        assertThat(verified.callback()).isNull();
    }

    @Test
    void buildsFreshSignedQueryRequestFromPersistedAttemptAndUtcClock() {
        var order = com.drawingcourse.backend.entity.Order.create(7,
                java.util.List.of(new com.drawingcourse.backend.entity.OrderLineSnapshot(
                        com.drawingcourse.backend.entity.OrderItemType.COURSE, 8, "Course", 1, 12_345, true)),
                0, java.util.Optional.empty(), Clock.fixed(Instant.parse("2026-11-04T05:00:00Z"), ZoneOffset.UTC));
        var attempt = com.drawingcourse.backend.entity.PaymentAttempt.create(4, order, "vnpay", "dca-attempt-1",
                Clock.fixed(Instant.parse("2026-11-04T05:00:00Z"), ZoneOffset.UTC));
        var props = properties();
        props.getQueryDr().setEnabled(true);
        props.getQueryDr().setIpAddress("127.0.0.1");
        var request = VnPayQueryDrService.buildRequest(props, attempt, "Payment for order 4",
                Instant.parse("2026-11-04T05:01:00Z"));
        assertThat(request.get("vnp_RequestId")).hasSize(32);
        assertThat(request.get("vnp_Command")).isEqualTo("querydr");
        assertThat(request.get("vnp_TransactionDate")).isEqualTo("20261104120000");
        assertThat(request.get("vnp_CreateDate")).isEqualTo("20261104120100");
        assertThat(request.get("vnp_SecureHash")).isEqualTo(
                VnPaySignature.hmacSha512Hex(SECRET, VnPayQueryDrService.requestCanonical(request)));
    }

    private static VnPayProperties properties() {
        var properties = new VnPayProperties();
        properties.setEnabled(true);
        properties.setTmnCode("DEMOV210");
        properties.setSecretKey(SECRET);
        properties.setReturnUrl("https://merchant.example.test/return");
        properties.setIpnUrl("https://merchant.example.test/ipn");
        return properties;
    }

    private static Map<String, String> signedResponse(String status) {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("vnp_ResponseId", "resp-123");
        response.put("vnp_Command", "querydr");
        response.put("vnp_ResponseCode", "00");
        response.put("vnp_Message", "Success");
        response.put("vnp_TmnCode", "DEMOV210");
        response.put("vnp_TxnRef", "dca-attempt-1");
        response.put("vnp_Amount", "1234500");
        response.put("vnp_BankCode", "NCB");
        response.put("vnp_PayDate", "20261104120000");
        response.put("vnp_TransactionNo", "123456789");
        response.put("vnp_TransactionType", "01");
        response.put("vnp_TransactionStatus", status);
        response.put("vnp_OrderInfo", "Payment for order 4");
        response.put("vnp_PromotionCode", "");
        response.put("vnp_PromotionAmount", "");
        sign(response);
        return response;
    }

    private static void sign(Map<String, String> response) {
        response.put("vnp_SecureHash", VnPaySignature.hmacSha512Hex(SECRET,
                VnPayQueryDrVerifier.responseCanonical(response)));
    }
}
