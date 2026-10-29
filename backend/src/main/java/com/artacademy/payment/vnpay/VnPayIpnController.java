package com.artacademy.payment.vnpay;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Gated VNPay IPN HTTP boundary. It records verified evidence; it never settles an Order. */
@RestController
@RequestMapping("/api/payments/vnpay")
@ConditionalOnProperty(prefix = "app.payment.vnpay", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.payment.vnpay.ipn", name = "enabled", havingValue = "true")
public final class VnPayIpnController {
    private static final int MAX_PARAMETER_COUNT = 64;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 4096;
    private static final int MAX_TOTAL_LENGTH = 16384;

    private final VnPayCallbackVerifier verifier;
    private final VnPayIpnReconciliationService reconciliation;

    public VnPayIpnController(VnPayCallbackVerifier verifier, VnPayIpnReconciliationService reconciliation) {
        this.verifier = Objects.requireNonNull(verifier);
        this.reconciliation = Objects.requireNonNull(reconciliation);
    }

    @GetMapping(path = "/ipn", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Acknowledgement> ipn(@RequestParam MultiValueMap<String, String> parameters) {
        Map<String, List<String>> preserved = preserveBoundedParameters(parameters);
        if (preserved == null) return ResponseEntity.ok(ack("99", "Invalid callback"));

        final VerifiedVnPayCallback verified;
        try {
            verified = verifier.verify(preserved);
        } catch (IllegalArgumentException invalid) {
            // Do not echo the verifier message: it may name callback fields and is not protocol data.
            String code = invalid.getMessage() != null && invalid.getMessage().contains("signature")
                    ? "97" : "99";
            return ResponseEntity.ok(ack(code, "Callback verification failed"));
        } catch (RuntimeException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ack("99", "Callback unavailable"));
        }

        try {
            // reconcile() returns only after its REQUIRED transaction has committed. A successful
            // acknowledgement means the audit/reconciliation decision is durable, not that payment settled.
            reconciliation.reconcile(verified);
            return ResponseEntity.ok(ack("00", "Callback recorded"));
        } catch (RuntimeException persistenceOrProcessingFailure) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ack("99", "Callback unavailable"));
        }
    }

    private static Map<String, List<String>> preserveBoundedParameters(MultiValueMap<String, String> parameters) {
        if (parameters == null || parameters.size() > MAX_PARAMETER_COUNT) return null;
        Map<String, List<String>> copy = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (key == null || key.length() > MAX_KEY_LENGTH || values == null || values.isEmpty()) return null;
            total += key.length();
            if (total > MAX_TOTAL_LENGTH) return null;
            List<String> valueCopy = new ArrayList<>(values.size());
            for (String value : values) {
                if (value == null || value.length() > MAX_VALUE_LENGTH) return null;
                total += value.length();
                if (total > MAX_TOTAL_LENGTH) return null;
                valueCopy.add(value);
            }
            copy.put(key, List.copyOf(valueCopy));
        }
        return Map.copyOf(copy);
    }

    private static Acknowledgement ack(String code, String message) { return new Acknowledgement(code, message); }

    /** Property names intentionally preserve VNPay's acknowledgement JSON casing. */
    public record Acknowledgement(String RspCode, String Message) { }
}
