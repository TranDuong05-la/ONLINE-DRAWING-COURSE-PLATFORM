package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.PaymentAttempt;
import com.drawingcourse.backend.entity.PaymentAttemptJpaEntity;
import com.drawingcourse.backend.entity.PaymentStatus;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VnPayQueryDrServiceTest {
    private static final String SECRET = "synthetic-querydr-service-secret";
    private static final Instant CREATED = Instant.parse("2026-11-04T05:00:00Z");
    private static final String MERCHANT_REFERENCE = "dca-local-demo-reference";

    @Test
    void signedProviderSuccessUsesPersistedAttemptAndEntersExistingReconciliation() {
        VnPayProperties properties = configured();
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentAttemptJpaEntity row = attemptRow();
        when(attempts.findByProviderAndMerchantReference("vnpay", MERCHANT_REFERENCE)).thenReturn(Optional.of(row));
        AtomicReference<Map<String, String>> capturedRequest = new AtomicReference<>();
        VnPayQueryDrTransport transport = request -> {
            capturedRequest.set(request);
            return response("00");
        };
        VnPayIpnReconciliationService reconciliation = mock(VnPayIpnReconciliationService.class);
        when(reconciliation.reconcileQueryDr(any())).thenReturn(new IpnReconciliationResult(
                IpnClassification.VALID_ON_TIME_SUCCESS, 33, 4, 91, null, null, null, false));
        var service = new VnPayQueryDrService(properties, attempts, transport,
                new VnPayQueryDrVerifier(properties), reconciliation,
                Clock.fixed(CREATED.plusSeconds(30), ZoneOffset.UTC));

        var result = service.refreshAfterBrowserReturn(MERCHANT_REFERENCE);

        assertThat(result.verifiedPaymentSuccess()).isTrue();
        assertThat(result.reconciliationClassification()).isEqualTo(IpnClassification.VALID_ON_TIME_SUCCESS);
        assertThat(capturedRequest.get()).containsEntry("vnp_TxnRef", MERCHANT_REFERENCE)
                .containsEntry("vnp_OrderInfo", "Payment for order 4")
                .doesNotContainKey("vnp_Amount");
        verify(reconciliation).reconcileQueryDr(any(VerifiedVnPayCallback.class));
    }

    @Test
    void browserSuccessParametersAreNotUsedAndSignedNonSuccessDoesNotReconcile() {
        VnPayProperties properties = configured();
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentAttemptJpaEntity row = attemptRow();
        when(attempts.findByProviderAndMerchantReference("vnpay", MERCHANT_REFERENCE))
                .thenReturn(Optional.of(row));
        VnPayIpnReconciliationService reconciliation = mock(VnPayIpnReconciliationService.class);
        var service = new VnPayQueryDrService(properties, attempts, request -> response("01"),
                new VnPayQueryDrVerifier(properties), reconciliation,
                Clock.fixed(CREATED.plusSeconds(30), ZoneOffset.UTC));

        assertThat(service.refreshAfterBrowserReturn(MERCHANT_REFERENCE).verifiedPaymentSuccess()).isFalse();
        verifyNoInteractions(reconciliation);
    }

    @Test
    void unknownBrowserReferenceDoesNotMakeExternalQuery() {
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        VnPayQueryDrTransport transport = mock(VnPayQueryDrTransport.class);
        var service = new VnPayQueryDrService(configured(), attempts, transport,
                new VnPayQueryDrVerifier(configured()), mock(VnPayIpnReconciliationService.class),
                Clock.fixed(CREATED.plusSeconds(30), ZoneOffset.UTC));
        when(attempts.findByProviderAndMerchantReference("vnpay", MERCHANT_REFERENCE)).thenReturn(Optional.empty());

        assertThat(service.refreshAfterBrowserReturn(MERCHANT_REFERENCE).verifiedPaymentSuccess()).isFalse();
        verifyNoInteractions(transport);
    }

    private static VnPayProperties configured() {
        var p = new VnPayProperties();
        p.setEnabled(true); p.setTmnCode("DEMOV210"); p.setSecretKey(SECRET);
        p.setReturnUrl("http://10.0.2.2:8080/api/payments/vnpay/return");
        p.setAllowLocalHttpReturnUrl(true);
        p.getQueryDr().setEnabled(true); p.getQueryDr().setIpAddress("127.0.0.1");
        return p;
    }

    private static PaymentAttemptJpaEntity attemptRow() {
        PaymentAttemptJpaEntity row = mock(PaymentAttemptJpaEntity.class);
        when(row.getId()).thenReturn(33);
        when(row.getOrderId()).thenReturn(4);
        when(row.getUserId()).thenReturn(7);
        when(row.getProvider()).thenReturn("vnpay");
        when(row.getMerchantReference()).thenReturn(MERCHANT_REFERENCE);
        when(row.getExpectedAmountVnd()).thenReturn(12345L);
        when(row.getCurrency()).thenReturn("VND");
        when(row.getOrderCreatedAt()).thenReturn(CREATED);
        when(row.getExpiresAt()).thenReturn(CREATED.plusSeconds(1800));
        when(row.getCreatedAt()).thenReturn(CREATED.plusSeconds(1));
        when(row.getUpdatedAt()).thenReturn(CREATED.plusSeconds(1));
        when(row.getStatus()).thenReturn(PaymentStatus.PENDING);
        when(row.getReviewReason()).thenReturn(PaymentAttempt.ReviewReason.NONE);
        return row;
    }

    private static Map<String, String> response(String status) {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("vnp_ResponseId", "response-1");
        response.put("vnp_Command", "querydr");
        response.put("vnp_ResponseCode", "00");
        response.put("vnp_Message", "Success");
        response.put("vnp_TmnCode", "DEMOV210");
        response.put("vnp_TxnRef", MERCHANT_REFERENCE);
        response.put("vnp_Amount", "1234500");
        response.put("vnp_BankCode", "NCB");
        response.put("vnp_PayDate", "20261104120010");
        response.put("vnp_TransactionNo", "123456789");
        response.put("vnp_TransactionType", "01");
        response.put("vnp_TransactionStatus", status);
        response.put("vnp_OrderInfo", "Payment for order 4");
        response.put("vnp_PromotionCode", "");
        response.put("vnp_PromotionAmount", "");
        response.put("vnp_SecureHash", VnPaySignature.hmacSha512Hex(SECRET,
                VnPayQueryDrVerifier.responseCanonical(response)));
        return Map.copyOf(response);
    }
}
