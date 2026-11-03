package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static com.drawingcourse.backend.entity.RefundPersistenceTypes.ProviderAttemptStatus;

@Entity
@Table(name = "refund_provider_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_refund_attempt_number", columnNames = {"refund_request_id", "attempt_number"}),
        @UniqueConstraint(name = "uk_refund_attempt_execution_slot", columnNames = {"refund_request_id", "execution_slot"}),
        @UniqueConstraint(name = "uk_refund_attempt_operation", columnNames = {"provider", "provider_operation_key"}),
        @UniqueConstraint(name = "uk_refund_attempt_provider_reference", columnNames = {"provider", "provider_reference"}),
        @UniqueConstraint(name = "uk_refund_attempt_id_request", columnNames = {"id", "refund_request_id"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundProviderAttemptJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "refund_request_id", nullable = false, updatable = false) private Long refundRequestId;
    @Column(name = "attempt_number", nullable = false, updatable = false) private int attemptNumber;
    @Column(nullable = false, length = 64, updatable = false) private String provider;
    @Column(name = "provider_operation_key", nullable = false, length = 128, updatable = false) private String providerOperationKey;
    @Column(name = "provider_reference", length = 255) private String providerReference;
    @Column(name = "amount_vnd", nullable = false, updatable = false) private long amountVnd;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "attempt_status", nullable = false, length = 24) private ProviderAttemptStatus attemptStatus;
    @Column(name = "execution_slot") private Byte executionSlot;
    @Column(name = "request_fingerprint", nullable = false, length = 64,
            columnDefinition = "CHAR(64)", updatable = false) private String requestFingerprint;
    @Column(name = "response_fingerprint", length = 64,
            columnDefinition = "CHAR(64)") private String responseFingerprint;
    @Column(name = "evidence_reference", length = 255) private String evidenceReference;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "started_at") private Instant startedAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "evidence_observed_at") private Instant evidenceObservedAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "completed_at") private Instant completedAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "retry_authorized_at") private Instant retryAuthorizedAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_request_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_refund_attempt_request"))
    private RefundRequestJpaEntity requestReference;

    public static RefundProviderAttemptJpaEntity pending(Long requestId, int number, String provider,
                                                         String operationKey, long amount, String currency,
                                                         String requestFingerprint, Instant at) {
        if (requestId == null || requestId <= 0 || number <= 0 || amount <= 0) throw new IllegalArgumentException("Invalid provider attempt");
        var row = new RefundProviderAttemptJpaEntity();
        row.refundRequestId = requestId; row.attemptNumber = number; row.provider = bounded(provider, 64);
        row.providerOperationKey = bounded(operationKey, 128); row.amountVnd = amount; row.currency = bounded(currency, 3);
        row.requestFingerprint = sha(requestFingerprint); row.attemptStatus = ProviderAttemptStatus.PENDING;
        row.executionSlot = 1; row.createdAt = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MICROS); row.updatedAt = row.createdAt;
        return row;
    }

    public void start(Instant at) { require(ProviderAttemptStatus.PENDING); attemptStatus = ProviderAttemptStatus.PROCESSING; startedAt = time(at); updatedAt = startedAt; }
    public void markUnknown(String fingerprint, String evidence, Instant at) { require(ProviderAttemptStatus.PROCESSING); attemptStatus = ProviderAttemptStatus.UNKNOWN; evidence(fingerprint, evidence, at); }
    public void succeed(String providerReference, String fingerprint, String evidence, Instant at) {
        if (attemptStatus != ProviderAttemptStatus.PROCESSING && attemptStatus != ProviderAttemptStatus.UNKNOWN) throw new IllegalStateException("Attempt cannot succeed");
        this.providerReference = bounded(providerReference, 255); attemptStatus = ProviderAttemptStatus.SUCCEEDED;
        evidence(fingerprint, evidence, at); completedAt = time(at);
    }
    public void recordDefinitiveFailure(String fingerprint, String evidence, Instant at) {
        if (attemptStatus != ProviderAttemptStatus.PROCESSING && attemptStatus != ProviderAttemptStatus.UNKNOWN) throw new IllegalStateException("Attempt cannot fail");
        attemptStatus = ProviderAttemptStatus.DEFINITIVE_FAILURE; evidence(fingerprint, evidence, at); completedAt = time(at);
    }
    public void authorizeRetry(Instant at) {
        require(ProviderAttemptStatus.DEFINITIVE_FAILURE); executionSlot = null; retryAuthorizedAt = time(at); updatedAt = retryAuthorizedAt;
    }
    private void evidence(String fingerprint, String reference, Instant at) { responseFingerprint = sha(fingerprint); evidenceReference = bounded(reference, 255); evidenceObservedAt = time(at); updatedAt = evidenceObservedAt; }
    private void require(ProviderAttemptStatus expected) { if (attemptStatus != expected) throw new IllegalStateException("Provider attempt is " + attemptStatus); }
    private static Instant time(Instant value) { return Objects.requireNonNull(value).truncatedTo(ChronoUnit.MICROS); }
    private static String bounded(String value, int max) { String v = Objects.requireNonNull(value).strip(); if (v.isEmpty() || v.length() > max) throw new IllegalArgumentException("Invalid bounded text"); return v; }
    private static String sha(String value) { String v = Objects.requireNonNull(value).toLowerCase(java.util.Locale.ROOT); if (!v.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("fingerprint"); return v; }
}
