package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.PaymentEventPolicy;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
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
import java.util.Set;
import java.util.TreeSet;

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
    private final PaymentReconciliationCaseWriter caseWriter;
    private final OrderRepository orders;
    private final VnPayCallbackVerifier verifier;
    private final Clock clock;
    private final PaymentPersistenceMapper paymentMapper = new PaymentPersistenceMapper();
    private final OrderPersistenceMapper orderMapper = new OrderPersistenceMapper();
    private final PaymentEventPolicy policy = new PaymentEventPolicy();
    private final VnPayPaymentStatusClassifier statusClassifier = new VnPayPaymentStatusClassifier();
    private final TransactionTemplate transaction;

    public VnPayIpnReconciliationService(VnPayProperties properties, PaymentAttemptRepository attempts,
            PaymentEventRepository events, PaymentEventConflictRepository conflicts,
            PaymentReconciliationCaseRepository cases, OrderRepository orders,
            VnPayCallbackVerifier verifier, Clock clock, PlatformTransactionManager transactionManager) {
        this.properties = Objects.requireNonNull(properties);
        this.attempts = Objects.requireNonNull(attempts);
        this.events = Objects.requireNonNull(events);
        this.conflicts = Objects.requireNonNull(conflicts);
        this.caseWriter = new PaymentReconciliationCaseWriter(cases);
        this.orders = Objects.requireNonNull(orders);
        this.verifier = Objects.requireNonNull(verifier);
        this.clock = Objects.requireNonNull(clock);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** Construction of this argument is restricted to the callback verifier package. */
    public IpnReconciliationResult reconcile(VerifiedVnPayCallback callback) {
        properties.validateReady();
        Objects.requireNonNull(callback, "verified callback");
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        VerifiedVnPayCallback verified = verifier.verify(asSingleValueLists(callback));
        boolean participatingInOuterTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return transaction.execute(status -> reconcileInTransaction(verified, receivedAt));
        } catch (PaymentReconciliationCaseWriter.ConcurrentCaseInsertException
                 | ConcurrentPaymentEventInsertException concurrentInsert) {
            // The unique violation must roll back before the winner is re-read. Never retry inside a
            // caller-owned transaction, where rollback is still the caller's responsibility.
            if (participatingInOuterTransaction) throw concurrentInsert;
            return transaction.execute(status -> reconcileInTransaction(verified, receivedAt));
        }
    }

    private IpnReconciliationResult reconcileInTransaction(VerifiedVnPayCallback verified, Instant receivedAt) {
        String merchantReference = verified.merchantReference();
        long amountMinor = Long.parseLong(verified.amountMinorUnits());
        var statusResult = statusClassifier.classify(verified.responseCode(), verified.transactionStatus());
        var timestampWithoutAttempt = providerTimestamp(verified.providerPaymentDate(), receivedAt, null);
        String digest = payloadDigest(verified.signedParameters());
        var routed = attempts.findRouteByProviderAndMerchantReference(PROVIDER, merchantReference);
        if (routed.isEmpty()) {
            String eventReference = usableTransactionReference(verified.transactionReference())
                    ? merchantReference + ":" + verified.transactionReference()
                    : merchantReference + ":unusable:" + sha256Hex(verified.transactionReference());
            caseWriter.write(PaymentReconciliationCaseJpaEntity.unknownMerchant(PROVIDER, eventReference,
                    "UNKNOWN_MERCHANT_REFERENCE", evidenceFingerprint(digest), merchantReference, amountMinor,
                    statusResult.eventOutcome().name(), caseTimestampState(timestampWithoutAttempt.state()),
                    hasTimestampValue(timestampWithoutAttempt) ? timestampWithoutAttempt.occurredAt() : null,
                    receivedAt, digest));
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
        if (!usableTransactionReference(transactionReference)) {
            var unusableTimestamp = providerTimestamp(verified.providerPaymentDate(), receivedAt, attempt.createdAt());
            String unusableEventReference = merchantReference + ":unusable:" + sha256Hex(transactionReference);
            PaymentStatus nextStatus = attempt.status() == PaymentStatus.SUCCEEDED
                    ? PaymentStatus.SUCCEEDED : PaymentStatus.UNKNOWN;
            PaymentAttempt.ReviewReason reviewReason = attempt.requiresReconciliation()
                    ? attempt.reviewReason() : PaymentAttempt.ReviewReason.ROUTING_MISMATCH;
            Instant updatedAt = receivedAt.isBefore(attempt.updatedAt()) ? attempt.updatedAt() : receivedAt;
            var uncertainAttempt = new PaymentAttempt(attempt.id(), attempt.orderId(), attempt.userId(), attempt.provider(),
                    attempt.merchantReference(), attempt.expectedAmountVnd(), attempt.currency(), attempt.orderCreatedAt(),
                    attempt.expiresAt(), attempt.createdAt(), updatedAt, nextStatus, attempt.transactionReference(),
                    attempt.succeededAt(), reviewReason);
            attemptRow.applyDecision(uncertainAttempt);
            caseWriter.write(PaymentReconciliationCaseJpaEntity.unusableReference(PROVIDER, unusableEventReference,
                    "UNUSABLE_TRANSACTION_REFERENCE", evidenceFingerprint(digest), attempt.id(), order.getId(),
                    merchantReference, amountMinor, statusResult.eventOutcome().name(),
                    caseTimestampState(unusableTimestamp.state()),
                    hasTimestampValue(unusableTimestamp) ? unusableTimestamp.occurredAt() : null,
                    receivedAt, digest));
            return new IpnReconciliationResult(IpnClassification.UNUSABLE_TRANSACTION_REFERENCE, attempt.id(),
                    order.getId(), null, null, PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,
                    reviewReason, false);
        }
        String eventReference = merchantReference + ":" + transactionReference;
        var timestamp = providerTimestamp(verified.providerPaymentDate(), receivedAt, attempt.createdAt());
        var outcome = statusResult.eventOutcome();
        boolean fractionalVnd = amountMinor % 100L != 0;
        long amountVnd = amountMinor / 100L;
        String eventCurrency = "VND"; // The VNPay payment contract is VND-only.
        var event = new PaymentEvent(PROVIDER, eventReference, merchantReference, transactionReference,
                amountVnd, eventCurrency, outcome, timestamp.occurredAt(), receivedAt,
                digest, verificationReference(timestamp.state()));

        var previousRow = events.findByProviderAndEventReference(PROVIDER, eventReference);
        if (previousRow.isPresent()) {
            var previous = paymentMapper.toDomain(previousRow.get());
            if (!Objects.equals(previous.attemptId(), attempt.id())
                    || !Objects.equals(previous.orderId(), order.getId())
                    || !Objects.equals(previous.userId(), order.getUserId())
                    || !Objects.equals(previous.event().provider(), PROVIDER)
                    || !Objects.equals(previous.event().eventReference(), eventReference)
                    || !Objects.equals(previous.event().fingerprint(), previous.fingerprint()))
                throw new IllegalStateException("Persisted PaymentEvent identity or route is inconsistent");
            try {
                policy.evaluate(attempt, event, order.getStatus(), Optional.of(previous));
                persistLinkedCases(previous, null, amountMinor, timestamp, statusResult,
                        order.getStatus(), receivedAt, fractionalVnd);
                return new IpnReconciliationResult(IpnClassification.DUPLICATE_CALLBACK, attempt.id(),
                        order.getId(), previous.id(), null, previous.disposition(), previous.reason(), true);
            } catch (IllegalStateException mismatch) {
                var priorFingerprint = previous.fingerprint();
                var conflictFingerprint = event.fingerprint();
                var existingConflict = conflicts.findByFirstEventIdAndConflictingFingerprint(previous.id(), conflictFingerprint);
                if (existingConflict.isPresent()) {
                    caseWriter.write(PaymentReconciliationCaseJpaEntity.linked(PROVIDER, eventReference,
                            "CONFLICTING_CALLBACK", conflictFingerprint, previous.id(), existingConflict.get().getId(),
                            attempt.id(), order.getId(), null, receivedAt));
                    return new IpnReconciliationResult(IpnClassification.CONFLICTING_CALLBACK, attempt.id(),
                            order.getId(), previous.id(), existingConflict.get().getId(),
                            PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,
                            null, true);
                }
                var conflict = PaymentEventConflictJpaEntity.verifiedConflict(previous, event, attempt, order.getStatus());
                var savedConflict = conflicts.saveAndFlush(conflict);
                attemptRow.applyDecision(quarantine(attempt, previous.event(), event, receivedAt));
                caseWriter.write(PaymentReconciliationCaseJpaEntity.linked(PROVIDER, eventReference,
                        "CONFLICTING_CALLBACK", conflictFingerprint, previous.id(), savedConflict.getId(),
                        attempt.id(), order.getId(), null, receivedAt));
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
        PaymentEventJpaEntity savedEvent;
        try {
            savedEvent = events.saveAndFlush(PaymentEventJpaEntity.newSnapshot(decision.audit()));
        } catch (RuntimeException insertFailure) {
            if (isExactPaymentEventIdentityDuplicate(insertFailure))
                throw new ConcurrentPaymentEventInsertException(insertFailure);
            throw insertFailure;
        }
        persistLinkedCases(paymentMapper.toDomain(savedEvent), null, amountMinor, timestamp, statusResult,
                order.getStatus(), receivedAt, fractionalVnd);
        return new IpnReconciliationResult(classify(decision, order.getStatus(), timestamp.valid(), fractionalVnd,
                statusResult.classification()), attempt.id(), order.getId(), savedEvent.getId(),
                null, decision.audit().disposition(), decision.audit().reason(), false);
    }

    private void persistLinkedCases(PaymentEventAudit audit, Integer conflictId, long amountMinor,
            ProviderTimestamp timestamp, VnPayPaymentStatusClassifier.Result statusResult,
            OrderStatus orderStatus, Instant receivedAt, boolean fractionalVnd) {
        if (audit.id() == null) throw new IllegalStateException("Reconciliation case requires a persisted PaymentEvent");
        Set<String> caseTypes = new TreeSet<>();
        if (fractionalVnd || audit.reason() == PaymentAttempt.ReviewReason.AMOUNT_MISMATCH)
            caseTypes.add("AMOUNT_MISMATCH");
        if (audit.reason() == PaymentAttempt.ReviewReason.CURRENCY_MISMATCH)
            caseTypes.add("CURRENCY_MISMATCH");
        if (audit.reason() == PaymentAttempt.ReviewReason.LATE_SUCCESS)
            caseTypes.add("LATE_SUCCESS");
        if (audit.reason() == PaymentAttempt.ReviewReason.ORDER_NOT_PAYABLE || orderStatus != OrderStatus.PENDING_PAYMENT)
            caseTypes.add("ORDER_NOT_PAYABLE");
        if (!timestamp.valid()) caseTypes.add("INVALID_PROVIDER_TIMESTAMP");
        if (audit.event().outcome() == PaymentEvent.Outcome.UNKNOWN
                || statusResult.classification() == VnPayPaymentStatusClassifier.Classification.UNKNOWN_OR_CONTRADICTORY
                || statusResult.classification() == VnPayPaymentStatusClassifier.Classification.SUSPICIOUS_DEBIT_SUCCESS)
            caseTypes.add("UNKNOWN_PAYMENT_STATUS");
        for (String caseType : caseTypes) {
            Long exactMinorAmount = "AMOUNT_MISMATCH".equals(caseType) ? amountMinor : null;
            caseWriter.write(PaymentReconciliationCaseJpaEntity.linked(PROVIDER, audit.event().eventReference(),
                    caseType, audit.fingerprint(), audit.id(), conflictId, audit.attemptId(), audit.orderId(),
                    exactMinorAmount, receivedAt));
        }
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
            boolean valid = !parsed.isAfter(receivedAt)
                    && (attemptCreatedAt == null || !parsed.isBefore(attemptCreatedAt));
            return new ProviderTimestamp(parsed, valid, valid ? "valid" : "out-of-range");
        } catch (RuntimeException invalid) {
            return new ProviderTimestamp(Instant.EPOCH, false, "invalid");
        }
    }

    private static boolean usableTransactionReference(String reference) {
        // The public VNPay PAY specification describes vnp_TransactionNo as numeric, 1 to 15 digits.
        return reference != null && reference.matches("[0-9]{1,15}");
    }

    private static boolean hasTimestampValue(ProviderTimestamp timestamp) {
        return "valid".equals(timestamp.state()) || "out-of-range".equals(timestamp.state());
    }

    private static String caseTimestampState(String state) {
        return switch (state) {
            case "valid" -> "VALID";
            case "missing" -> "MISSING";
            case "invalid" -> "MALFORMED";
            case "out-of-range" -> "OUT_OF_RANGE";
            default -> throw new IllegalArgumentException("Unsupported provider timestamp state");
        };
    }

    private static String evidenceFingerprint(String payloadDigest) { return "v1:" + payloadDigest; }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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

    static boolean isExactPaymentEventIdentityDuplicate(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                boolean duplicateKey = "23505".equals(sql.getSQLState())
                        || ("23000".equals(sql.getSQLState()) && sql.getErrorCode() == 1062);
                if (duplicateKey && namesConstraint(sql.getMessage(), "uk_payment_event")) return true;
            }
            if (current instanceof DataIntegrityViolationException
                    && namesConstraint(current.getMessage(), "uk_payment_event")) {
                // Some Hibernate translations retain the database constraint name on the wrapper.
                for (Throwable cause = current.getCause(); cause != null; cause = cause.getCause())
                    if (cause instanceof SQLException sql && ("23505".equals(sql.getSQLState())
                            || ("23000".equals(sql.getSQLState()) && sql.getErrorCode() == 1062))) return true;
            }
        }
        return false;
    }

    private static boolean namesConstraint(String message, String expected) {
        if (message == null) return false;
        return java.util.regex.Pattern.compile("(?<![a-z0-9_])" + java.util.regex.Pattern.quote(expected)
                + "(?![a-z0-9_])", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(message).find();
    }

    static final class ConcurrentPaymentEventInsertException extends RuntimeException {
        ConcurrentPaymentEventInsertException(Throwable cause) {
            super("Concurrent V5 PaymentEvent identity insert; transaction must roll back and be retried", cause);
        }
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
            boolean timestampValid, boolean fractionalVnd,
            VnPayPaymentStatusClassifier.Classification providerClassification) {
        var reason = decision.audit().reason();
        if (fractionalVnd || reason == PaymentAttempt.ReviewReason.AMOUNT_MISMATCH) return IpnClassification.AMOUNT_MISMATCH;
        if (reason == PaymentAttempt.ReviewReason.CURRENCY_MISMATCH) return IpnClassification.CURRENCY_MISMATCH;
        if (!timestampValid || reason == PaymentAttempt.ReviewReason.INVALID_EVENT_TIME) return IpnClassification.INVALID_PROVIDER_TIMESTAMP;
        if (reason == PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS) return IpnClassification.CONFLICTING_CALLBACK;
        if (decision.attempt().status() == PaymentStatus.SUCCEEDED && reason == PaymentAttempt.ReviewReason.LATE_SUCCESS)
            return IpnClassification.LATE_PROVIDER_SUCCESS;
        if (providerClassification == VnPayPaymentStatusClassifier.Classification.SUSPICIOUS_DEBIT_SUCCESS)
            return IpnClassification.SUSPICIOUS_PAYMENT_REQUIRES_REVIEW;
        if (orderStatus == OrderStatus.EXPIRED || orderStatus == OrderStatus.CANCELLED)
            return IpnClassification.EXPIRED_ORDER;
        if (decision.audit().event().outcome() == PaymentEvent.Outcome.UNKNOWN
                || providerClassification == VnPayPaymentStatusClassifier.Classification.UNKNOWN_OR_CONTRADICTORY)
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
