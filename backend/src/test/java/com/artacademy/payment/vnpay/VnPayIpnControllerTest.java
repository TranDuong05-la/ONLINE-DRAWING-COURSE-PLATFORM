package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.PaymentEventAudit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;

import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VnPayIpnControllerTest {
    private VnPayIpnReconciliationService reconciliation;
    private VnPayIpnController controller;

    @BeforeEach void setUp() {
        var properties = new VnPayProperties();
        properties.setEnabled(true);
        properties.setTmnCode("DEMOV210");
        properties.setSecretKey("unit-test-secret");
        properties.setReturnUrl("https://merchant.example.test/return");
        properties.setIpnUrl("https://merchant.example.test/ipn");
        var verifier = new VnPayCallbackVerifier(properties);
        reconciliation = mock(VnPayIpnReconciliationService.class);
        controller = new VnPayIpnController(verifier, reconciliation);
    }

    @Test void preservesRepeatedValuesForTheExistingVerifier() {
        var parameters = validParameters();
        parameters.add("vnp_TxnRef", "second-reference");

        var response = controller.ipn(parameters);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("99", response.getBody().RspCode());
        verifyNoInteractions(reconciliation);
    }

    @Test void invalidSignatureGetsProtocolFailureWithoutEchoingSignature() {
        var parameters = validParameters();
        parameters.set("vnp_SecureHash", "a".repeat(128));

        var response = controller.ipn(parameters);

        assertEquals("97", response.getBody().RspCode());
        assertFalse(response.getBody().Message().contains("a".repeat(128)));
        verifyNoInteractions(reconciliation);
    }

    @Test void unknownMerchantCodeFailsClosedWithoutUsingTheChecksumResponseCode() {
        var parameters = validParameters();
        parameters.set("vnp_TmnCode", "OTHER123");

        var response = controller.ipn(parameters);

        assertEquals("99", response.getBody().RspCode());
        verifyNoInteractions(reconciliation);
    }

    @Test void durableReconciliationAcknowledgesAuditOnlyAndDoesNotClaimOrderSettlement() {
        var parameters = validParameters();
        var verified = new VnPayCallbackVerifier(properties()).verify(parameters);
        when(reconciliation.reconcile(any(VerifiedVnPayCallback.class))).thenReturn(new IpnReconciliationResult(
                IpnClassification.VALID_ON_TIME_SUCCESS, 1, 2, 3, null,
                PaymentEventAudit.Disposition.APPLIED, null, false));

        var response = controller.ipn(parameters);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("00", response.getBody().RspCode());
        assertEquals("Callback recorded", response.getBody().Message());
        verify(reconciliation).reconcile(any(VerifiedVnPayCallback.class));
    }

    @Test void failedAuditPersistenceIsNotAcknowledgedAsSuccessful() {
        var parameters = validParameters();
        var verified = new VnPayCallbackVerifier(properties()).verify(parameters);
        when(reconciliation.reconcile(any(VerifiedVnPayCallback.class)))
                .thenThrow(new DataAccessResourceFailureException("db outage"));

        var response = controller.ipn(parameters);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("99", response.getBody().RspCode());
        assertFalse(response.getBody().Message().contains("db outage"));
    }

    @Test void missingFieldsAndOversizedInputAreRejectedBeforeReconciliation() {
        var missing = new LinkedMultiValueMap<String, String>();
        missing.add("vnp_TxnRef", "merchant-reference");
        assertEquals("99", controller.ipn(missing).getBody().RspCode());

        var oversized = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < 65; i++) oversized.add("x" + i, "v");
        assertEquals("99", controller.ipn(oversized).getBody().RspCode());
        verifyNoInteractions(reconciliation);
    }

    private VnPayProperties properties() {
        var properties = new VnPayProperties();
        properties.setEnabled(true);
        properties.setTmnCode("DEMOV210");
        properties.setSecretKey("unit-test-secret");
        properties.setReturnUrl("https://merchant.example.test/return");
        properties.setIpnUrl("https://merchant.example.test/ipn");
        return properties;
    }

    private static LinkedMultiValueMap<String, String> validParameters() {
        var signed = new TreeMap<String, String>();
        signed.put("vnp_TmnCode", "DEMOV210");
        signed.put("vnp_Amount", "1234500");
        signed.put("vnp_BankCode", "NCB");
        signed.put("vnp_OrderInfo", "unit callback");
        signed.put("vnp_TransactionNo", "987654321");
        signed.put("vnp_ResponseCode", "00");
        signed.put("vnp_TransactionStatus", "00");
        signed.put("vnp_TxnRef", "merchant-reference");
        String hash = VnPaySignature.hmacSha512Hex("unit-test-secret", VnPaySignature.canonical(signed));
        var parameters = new LinkedMultiValueMap<String, String>();
        signed.forEach(parameters::add);
        parameters.add("vnp_SecureHash", hash);
        return parameters;
    }
}
