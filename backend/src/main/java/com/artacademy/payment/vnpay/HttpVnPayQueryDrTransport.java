package com.artacademy.payment.vnpay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Fixed sandbox QueryDR endpoint with bounded network timeouts and response size. */
final class HttpVnPayQueryDrTransport implements VnPayQueryDrTransport {
    static final URI ENDPOINT = URI.create("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
    private static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper objectMapper;

    HttpVnPayQueryDrTransport(ObjectMapper objectMapper) { this.objectMapper = Objects.requireNonNull(objectMapper); }

    @Override
    public Map<String, String> query(Map<String, String> request) {
        try {
            byte[] requestBody = objectMapper.writeValueAsBytes(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
            HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200)
                    throw new IllegalStateException("VNPay QueryDR returned an unusable response");
                byte[] bodyBytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bodyBytes.length > MAX_RESPONSE_BYTES)
                    throw new IllegalStateException("VNPay QueryDR response exceeded the configured limit");
                Map<String, Object> decoded = objectMapper.readValue(bodyBytes, new TypeReference<>() { });
            Map<String, String> values = new LinkedHashMap<>();
            decoded.forEach((key, value) -> {
                if (key == null || value == null || !(value instanceof String || value instanceof Number))
                    throw new IllegalArgumentException("Malformed VNPay QueryDR response");
                values.put(key, value.toString());
            });
            return Map.copyOf(values);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("VNPay QueryDR communication failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("VNPay QueryDR was interrupted", ex);
        }
    }
}
