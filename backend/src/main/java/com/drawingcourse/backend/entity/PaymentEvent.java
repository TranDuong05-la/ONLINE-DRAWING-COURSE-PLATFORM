package com.drawingcourse.backend.entity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Normalized VERIFIED provider data, never an HTTP DTO. Construction does not verify authenticity. */
public record PaymentEvent(String provider, String eventReference, String merchantReference,
                           String transactionReference, long amountVnd, String currency, Outcome outcome,
                           Instant occurredAt, Instant receivedAt, String payloadSha256, String verificationReference) {
    public enum Outcome { PENDING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }
    public PaymentEvent {
        provider = reference(provider,64); eventReference = reference(eventReference,255);
        merchantReference = reference(merchantReference,128);
        if (transactionReference != null) transactionReference = reference(transactionReference,255);
        currency = reference(currency,16); verificationReference = reference(verificationReference,255);
        Objects.requireNonNull(outcome); Objects.requireNonNull(occurredAt); Objects.requireNonNull(receivedAt);
        if (amountVnd < 0 || payloadSha256 == null || !payloadSha256.matches("[a-f0-9]{64}")
                || (outcome == Outcome.SUCCEEDED && transactionReference == null))
            throw new IllegalArgumentException("Invalid verified payment event");
    }
    static String reference(String value, int maximum) {
        Objects.requireNonNull(value);
        if (value.isBlank() || !value.equals(value.strip()) || value.length()>maximum)
            throw new IllegalArgumentException("Invalid payment reference");
        return value;
    }
    /** Received time/verification execution IDs vary on replay; provider facts/body digest may not. */
    public String fingerprint() {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                for (var value : List.of("payment-event-v1",provider,eventReference,merchantReference,
                        currency,outcome.name(),payloadSha256)) write(out,value);
                out.writeBoolean(transactionReference != null);
                if (transactionReference != null) write(out,transactionReference);
                out.writeLong(amountVnd); out.writeLong(occurredAt.getEpochSecond()); out.writeInt(occurredAt.getNano());
            }
            return "v1:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void write(DataOutputStream out,String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
}
