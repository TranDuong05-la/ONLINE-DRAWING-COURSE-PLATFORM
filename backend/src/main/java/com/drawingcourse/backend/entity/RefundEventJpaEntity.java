package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

import static com.drawingcourse.backend.entity.RefundPersistenceTypes.EventOutcome;

@Entity
@Table(name = "refund_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_refund_event_identity", columnNames = {"refund_request_id", "operation_type", "event_key"}),
        @UniqueConstraint(name = "uk_refund_event_id_request", columnNames = {"id", "refund_request_id"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundEventJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "refund_request_id", nullable = false, updatable = false) private Long refundRequestId;
    @Column(name = "provider_attempt_id", updatable = false) private Long providerAttemptId;
    @Column(name = "operation_type", nullable = false, length = 48, updatable = false) private String operationType;
    @Column(name = "event_key", nullable = false, length = 255, updatable = false) private String eventKey;
    @Column(name = "event_fingerprint", nullable = false, length = 64,
            columnDefinition = "CHAR(64)", updatable = false) private String eventFingerprint;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_outcome", nullable = false, length = 24, updatable = false) private EventOutcome eventOutcome;
    @Column(name = "evidence_source", length = 64, updatable = false) private String evidenceSource;
    @Column(name = "evidence_reference", length = 255, updatable = false) private String evidenceReference;
    @Column(name = "evidence_version", length = 128, updatable = false) private String evidenceVersion;
    @Column(name = "evidence_fingerprint", length = 64,
            columnDefinition = "CHAR(64)", updatable = false) private String evidenceFingerprint;
    @Column(name = "state_before_fingerprint", nullable = false, length = 64,
            columnDefinition = "CHAR(64)", updatable = false) private String stateBeforeFingerprint;
    @Column(name = "state_after_fingerprint", nullable = false, length = 64,
            columnDefinition = "CHAR(64)", updatable = false) private String stateAfterFingerprint;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "occurred_at", updatable = false) private Instant occurredAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "evidence_observed_at", updatable = false) private Instant evidenceObservedAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "recorded_at", nullable = false, updatable = false) private Instant recordedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_request_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_refund_event_request"))
    private RefundRequestJpaEntity requestReference;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "provider_attempt_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "refund_request_id", referencedColumnName = "refund_request_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_refund_event_attempt_route"))
    private RefundProviderAttemptJpaEntity attemptReference;

    public static RefundEventJpaEntity applied(Long requestId, Long attemptId, String operation, String eventKey,
                                               String fingerprint, String before, String after,
                                               EvidenceSnapshot evidence, Instant occurredAt, Instant recordedAt) {
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("requestId");
        var row = new RefundEventJpaEntity(); row.refundRequestId = requestId; row.providerAttemptId = attemptId;
        row.operationType = bounded(operation, 48); row.eventKey = bounded(eventKey, 255); row.eventFingerprint = sha(fingerprint);
        row.eventOutcome = EventOutcome.APPLIED; row.stateBeforeFingerprint = sha(before); row.stateAfterFingerprint = sha(after);
        if (evidence != null) { row.evidenceSource = bounded(evidence.source, 64); row.evidenceReference = bounded(evidence.reference, 255); row.evidenceVersion = nullableBounded(evidence.version, 128); row.evidenceFingerprint = sha(evidence.fingerprint); row.evidenceObservedAt = time(evidence.observedAt); }
        row.occurredAt = occurredAt == null ? null : time(occurredAt); row.recordedAt = time(recordedAt); return row;
    }

    public record EvidenceSnapshot(String source, String reference, String version, String fingerprint, Instant observedAt) { }
    private static Instant time(Instant value) { return Objects.requireNonNull(value).truncatedTo(ChronoUnit.MICROS); }
    private static String bounded(String value, int max) { String v = Objects.requireNonNull(value).strip(); if (v.isEmpty() || v.length() > max) throw new IllegalArgumentException("Invalid bounded text"); return v; }
    private static String nullableBounded(String value, int max) { return value == null ? null : bounded(value, max); }
    private static String sha(String value) { String v = Objects.requireNonNull(value).toLowerCase(Locale.ROOT); if (!v.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("fingerprint"); return v; }
}
