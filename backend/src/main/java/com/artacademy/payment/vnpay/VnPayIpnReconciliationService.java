package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.PaymentEventPolicy;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Classifies and audits verified VNPay callbacks. It never mutates the Order or fulfills it. */
public class VnPayIpnReconciliationService {
    private static final String PROVIDER = "vnpay";
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private final VnPayProperties properties;
    private final PaymentAttemptRepository attempts;
    private final PaymentEventRepository events;
    private final PaymentEventConflictRepository conflicts;
    private final OrderRepository orders;
    private final VnPayCallbackVerifier verifier;
    private final Clock clock;
    private final PaymentPersistenceMapper paymentMapper = new PaymentPersistenceMapper();
    private final OrderPersistenceMapper orderMapper = new OrderPersistenceMapper();
    private final PaymentEventPolicy policy = new PaymentEventPolicy();

    public VnPayIpnReconciliationService(VnPayProperties properties, PaymentAttemptRepository attempts,
            PaymentEventRepository events, PaymentEventConflictRepository conflicts, OrderRepository orders,
            VnPayCallbackVerifier verifier, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.attempts = Objects.requireNonNull(attempts);
        this.events = Objects.requireNonNull(events);
        this.conflicts = Objects.requireNonNull(conflicts);
        this.orders = Objects.requireNonNull(orders);
        this.verifier = Objects.requireNonNull(verifier);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Construction of this argument is restricted to the callback verifier package. */
    @Transactional
    public IpnReconciliationResult reconcile(VerifiedVnPayCallback callback) {
        properties.validateReady();
        Objects.requireNonNull(callback, "verified callback");
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        VerifiedVnPayCallback verified = verifier.verify(asSingleValueLists(callback));
        String merchantReference = verified.merchantReference();
        var routed = attempts.findRouteByProviderAndMerchantReference(PROVIDER, merchantReference);
        if (routed.isEmpty()) {
            // V5 requires attempt/order/user FKs, so unmatched callbacks cannot be stored safely.
            return new IpnReconciliationResult(IpnClassification.UNKNOWN_MERCHANT_REFERENCE, null, null,
                    null, null, null, null, false);
        }
        var route = routed.get();
        if (!PROVIDER.equals(route.getProvider()) || !merchantReference.equals(route.getMerchantReference()))
            throw new IllegalStateException("Payment attempt route changed during lookup");

        // Lock order matches payment-session creation: Order first, then PaymentAttempt.
        var order = orders.findForUpdate(route.getOrderId(), route.getUserId())
                .orElseThrow(() -> new IllegalStateException("Payment attempt refers to a missing Order"));
        var attemptRow = attempts.findForUpdate(route.getId(), route.getUserId())
                .orElseThrow(() -> new IllegalStateException("Payment attempt disappeared during reconciliation"));
        if (!route.getOrderId().equals(order.getId()) || !route.getUserId().equals(order.getUserId())
                || !attemptRow.getOrderId().equals(order.getId()) || !attemptRow.getUserId().equals(order.getUserId())
                || !attemptRow.getProvider().equals(PROVIDER)
                || !attemptRow.getMerchantReference().equals(merchantReference))
            throw new IllegalStateException("Payment attempt and Order routing do not match");

        var attempt = paymentMapper.toDomain(attemptRow);
        // Restore the persisted Order to validate its identity/snapshot and its actual persisted currency.
        var orderSnapshot = orderMapper.toDomain(order);
        if (!attempt.orderId().equals(order.getId()) || !attempt.userId().equals(order.getUserId())
                || !attempt.currency().equals(orderSnapshot.getCurrency()))
            throw new IllegalStateException("Persisted Order and PaymentAttempt snapshots are inconsistent");
        String transactionReference = verified.transactionReference();
        String eventReference = merchantReference + ":" + transactionReference;
        if (eventReference.length() > 255) throw new IllegalArgumentException("VNPay event reference is too long");
        var timestamp = providerTimestamp(verified.providerPaymentDate(), receivedAt, attempt.createdAt());
        var outcome = status(verified.responseCode(), verified.transactionStatus());
        long amountMinor = Long.parseLong(verified.amountMinorUnits());
        boolean fractionalVnd = amountMinor % 100L != 0;
        long amountVnd = amountMinor / 100L;
        String eventCurrency = "VND"; // The VNPay payment contract is VND-only.
        var event = new PaymentEvent(PROVIDER, eventReference, merchantReference, transactionReference,
                amountVnd, eventCurrency, outcome, timestamp.occurredAt(), receivedAt,
                payloadDigest(verified.signedParameters()), verificationReference(timestamp.state()));

        var previousRow = events.findByProviderAndEventReference(PROVIDER, eventReference);
        if (previousRow.isPresent()) {
            var previous = paymentMapper.toDomain(previousRow.get());
            try {
                policy.evaluate(attempt, event, order.getStatus(), Optional.of(previous));
                return new IpnReconciliationResult(IpnClassification.DUPLICATE_CALLBACK, attempt.id(),
                        order.getId(), previous.id(), null, previous.disposition(), previous.reason(), true);
            } catch (IllegalStateException mismatch) {
                var priorFingerprint = previous.fingerprint();
                var conflictFingerprint = event.fingerprint();
                var existingConflict = conflicts.findByFirstEventIdAndConflictingFingerprint(previous.id(), conflictFingerprint);
                if (existingConflict.isPresent()) {
                    return new IpnReconciliationResult(IpnClassification.CONFLICTING_CALLBACK, attempt.id(),
                            order.getId(), previous.id(), existingConflict.get().getId(),
                            PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,
                            null, true);
                }
                var conflict = PaymentEventConflictJpaEntity.verifiedConflict(previous, event, attempt, order.getStatus());
                var savedConflict = conflicts.saveAndFlush(conflict);
                attemptRow.applyDecision(quarantine(attempt, previous.event(), event, receivedAt));
                return new IpnReconciliationResult(IpnClassification.CONFLICTING_CALLBACK, attempt.id(),
                        order.getId(), previous.id(), savedConflict.getId(),
                        PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,
                        null, false);
            }
        }

        var decision = policy.evaluate(attempt, event, order.getStatus(), Optional.empty());
        var reason = decision.audit().reason();
        if (fractionalVnd && reason == PaymentAttempt.ReviewReason.NONE)
            reason = PaymentAttempt.ReviewReason.AMOUNT_MISMATCH;
        if (!timestamp.valid() && reason == PaymentAttempt.ReviewReason.NONE)
            reason = PaymentAttempt.ReviewReason.INVALID_EVENT_TIME;
        if (reason != decision.audit().reason()) decision = requireReview(decision, reason);

        attemptRow.applyDecision(decision.attempt());
        var savedEvent = events.saveAndFlush(PaymentEventJpaEntity.newSnapshot(decision.audit()));
        return new IpnReconciliationResult(classify(decision, order.getStatus(), timestamp.valid(), fractionalVnd,
                verified.responseCode(), verified.transactionStatus()), attempt.id(), order.getId(), savedEvent.getId(),
                null, decision.audit().disposition(), decision.audit().reason(), false);
    }

    private static Map<String, java.util.List<String>> asSingleValueLists(VerifiedVnPayCallback callback) {
        var result = new java.util.LinkedHashMap<String, java.util.List<String>>();
        callback.signedParameters().forEach((key, value) -> result.put(key, java.util.List.of(value)));
        result.put("vnp_SecureHash", java.util.List.of(callback.signature()));
        return result;
    }

    private record ProviderTimestamp(Instant occurredAt, boolean valid, String state) { }

    private static ProviderTimestamp providerTimestamp(String value, Instant receivedAt, Instant attemptCreatedAt) {
        if (value == null || value.isBlank()) return new ProviderTimestamp(Instant.EPOCH, false, "missing");
        try {
            Instant parsed = LocalDateTime.parse(value, PAY_DATE).atZone(VN_ZONE).toInstant();
            boolean valid = !parsed.isAfter(receivedAt) && !parsed.isBefore(attemptCreatedAt);
            return new ProviderTimestamp(parsed, valid, valid ? "valid" : "out-of-range");
        } catch (RuntimeException invalid) {
            return new ProviderTimestamp(Instant.EPOCH, false, "invalid");
        }
    }

    private static PaymentEvent.Outcome status(String responseCode, String transactionStatus) {
        if (responseCode == null || transactionStatus == null || !responseCode.matches("[0-9]{2}")
                || !transactionStatus.matches("[0-9]{2}")) return PaymentEvent.Outcome.UNKNOWN;
        return "00".equals(responseCode) && "00".equals(transactionStatus)
                ? PaymentEvent.Outcome.SUCCEEDED : PaymentEvent.Outcome.FAILED;
    }

    private static String verificationReference(String timestampState) {
        return "vnpay-hmac-sha512;paydate=" + timestampState;
    }

    private static String payloadDigest(Map<String, String> parameters) {
        try {
            var canonical = new java.util.TreeMap<>(parameters).entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("&"));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static PaymentEventPolicy.Result requireReview(PaymentEventPolicy.Result original,
                                                           PaymentAttempt.ReviewReason reason) {
        var old = original.attempt();
        var stickyReason = old.requiresReconciliation() ? old.reviewReason() : reason;
        var attempt = new PaymentAttempt(old.id(), old.orderId(), old.userId(), old.provider(), old.merchantReference(),
                old.expectedAmountVnd(), old.currency(), old.orderCreatedAt(), old.expiresAt(), old.createdAt(),
                old.updatedAt(), old.status(), old.transactionReference(), old.succeededAt(), stickyReason);
        var priorAudit = original.audit();
        var audit = new PaymentEventAudit(null, priorAudit.attemptId(), priorAudit.orderId(), priorAudit.userId(),
                priorAudit.event(), priorAudit.fingerprint(), priorAudit.beforeStatus(), priorAudit.afterStatus(),
                PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED, reason, priorAudit.observedOrderStatus());
        return new PaymentEventPolicy.Result(attempt, audit, original.duplicate());
    }

    private static IpnClassification classify(PaymentEventPolicy.Result decision, OrderStatus orderStatus,
            boolean timestampValid, boolean fractionalVnd, String responseCode, String transactionStatus) {
        var reason = decision.audit().reason();
        if (fractionalVnd || reason == PaymentAttempt.ReviewReason.AMOUNT_MISMATCH) return IpnClassification.AMOUNT_MISMATCH;
        if (reason == PaymentAttempt.ReviewReason.CURRENCY_MISMATCH) return IpnClassification.CURRENCY_MISMATCH;
        if (!timestampValid || reason == PaymentAttempt.ReviewReason.INVALID_EVENT_TIME) return IpnClassification.INVALID_PROVIDER_TIMESTAMP;
        if (reason == PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS) return IpnClassification.CONFLICTING_CALLBACK;
        if (decision.attempt().status() == PaymentStatus.SUCCEEDED && reason == PaymentAttempt.ReviewReason.LATE_SUCCESS)
            return IpnClassification.LATE_PROVIDER_SUCCESS;
        if (orderStatus == OrderStatus.EXPIRED || orderStatus == OrderStatus.CANCELLED)
            return IpnClassification.EXPIRED_ORDER;
        if (decision.audit().event().outcome() == PaymentEvent.Outcome.UNKNOWN || responseCode == null
                || transactionStatus == null || !responseCode.matches("[0-9]{2}") || !transactionStatus.matches("[0-9]{2}"))
            return IpnClassification.UNKNOWN_OR_AMBIGUOUS_PAYMENT_STATUS;
        if (reason != PaymentAttempt.ReviewReason.NONE) return IpnClassification.OTHER_RECONCILIATION_REQUIRED;
        return decision.audit().event().outcome() == PaymentEvent.Outcome.SUCCEEDED
                ? IpnClassification.VALID_ON_TIME_SUCCESS : IpnClassification.VALID_FAILURE;
    }

    private static PaymentAttempt quarantine(PaymentAttempt current, PaymentEvent first,
                                             PaymentEvent conflicting, Instant receivedAt) {
        boolean successConflict = first.outcome() == PaymentEvent.Outcome.SUCCEEDED
                || conflicting.outcome() == PaymentEvent.Outcome.SUCCEEDED;
        PaymentStatus status = current.status() == PaymentStatus.SUCCEEDED ? PaymentStatus.SUCCEEDED : PaymentStatus.UNKNOWN;
        PaymentAttempt.ReviewReason reason = current.reviewReason();
        if (successConflict) reason = PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS;
        Instant updatedAt = receivedAt.isBefore(current.updatedAt()) ? current.updatedAt() : receivedAt;
        return new PaymentAttempt(current.id(), current.orderId(), current.userId(), current.provider(),
                current.merchantReference(), current.expectedAmountVnd(), current.currency(), current.orderCreatedAt(),
                current.expiresAt(), current.createdAt(), updatedAt, status, current.transactionReference(),
                current.succeededAt(), reason);
    }
}
