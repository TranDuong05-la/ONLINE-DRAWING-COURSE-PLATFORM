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

import static com.drawingcourse.backend.entity.RefundPersistenceTypes.ReviewStatus;

@Entity
@Table(name = "refund_review_cases", uniqueConstraints =
        @UniqueConstraint(name = "uk_refund_review_case_key", columnNames = {"refund_request_id", "case_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundReviewCaseJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "refund_request_id", nullable = false, updatable = false) private Long refundRequestId;
    @Column(name = "provider_attempt_id", updatable = false) private Long providerAttemptId;
    @Column(name = "original_event_id", updatable = false) private Long originalEventId;
    @Column(name = "case_type", nullable = false, length = 48, updatable = false) private String caseType;
    @Column(name = "case_key", nullable = false, length = 64,
            columnDefinition = "CHAR(64)", updatable = false) private String caseKey;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "case_status", nullable = false, length = 16) private ReviewStatus caseStatus;
    @Column(name = "original_fingerprint", length = 64,
            columnDefinition = "CHAR(64)", updatable = false) private String originalFingerprint;
    @Column(name = "conflicting_fingerprint", length = 64,
            columnDefinition = "CHAR(64)", updatable = false) private String conflictingFingerprint;
    @Column(name = "evidence_reference", length = 255, updatable = false) private String evidenceReference;
    @Column(name = "evidence_fingerprint", length = 64,
            columnDefinition = "CHAR(64)", updatable = false) private String evidenceFingerprint;
    @Column(name = "details_snapshot", columnDefinition = "TEXT", updatable = false) private String detailsSnapshot;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "opened_at", nullable = false, updatable = false) private Instant openedAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "resolution_code", length = 64) private String resolutionCode;
    @Column(name = "resolved_by_user_id") private Integer resolvedByUserId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_request_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_refund_review_request"))
    private RefundRequestJpaEntity requestReference;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "provider_attempt_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "refund_request_id", referencedColumnName = "refund_request_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_refund_review_attempt_route"))
    private RefundProviderAttemptJpaEntity attemptReference;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "original_event_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "refund_request_id", referencedColumnName = "refund_request_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_refund_review_event_route"))
    private RefundEventJpaEntity originalEventReference;

    public static RefundReviewCaseJpaEntity eventConflict(Long requestId, Long attemptId, Long originalEventId,
                                                          String caseKey, String originalFingerprint,
                                                          String conflictingFingerprint, String evidenceReference,
                                                          String evidenceFingerprint, String details, Instant at) {
        if (requestId == null || requestId <= 0 || originalEventId == null || originalEventId <= 0)
            throw new IllegalArgumentException("Invalid conflict route");
        var row = new RefundReviewCaseJpaEntity(); row.refundRequestId = requestId; row.providerAttemptId = attemptId;
        row.originalEventId = originalEventId; row.caseType = "EVENT_IDENTITY_CONFLICT"; row.caseKey = sha(caseKey);
        row.caseStatus = ReviewStatus.OPEN; row.originalFingerprint = sha(originalFingerprint);
        row.conflictingFingerprint = sha(conflictingFingerprint); row.evidenceReference = bounded(evidenceReference, 255);
        row.evidenceFingerprint = sha(evidenceFingerprint); row.detailsSnapshot = details; row.openedAt = time(at); return row;
    }

    public static RefundReviewCaseJpaEntity requestConflict(Long requestId, String caseKey,
                                                            String originalFingerprint, String conflictingFingerprint,
                                                            String details, Instant at) {
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("Invalid request route");
        var row = new RefundReviewCaseJpaEntity(); row.refundRequestId = requestId;
        row.caseType = "REQUEST_IDEMPOTENCY_CONFLICT"; row.caseKey = sha(caseKey); row.caseStatus = ReviewStatus.OPEN;
        row.originalFingerprint = sha(originalFingerprint); row.conflictingFingerprint = sha(conflictingFingerprint);
        row.detailsSnapshot = details; row.openedAt = time(at); return row;
    }

    public void resolve(Integer adminId, String code, Instant at) {
        if (caseStatus != ReviewStatus.OPEN) throw new IllegalStateException("Review case is closed");
        if (adminId == null || adminId <= 0) throw new IllegalArgumentException("adminId");
        caseStatus = ReviewStatus.RESOLVED; resolvedByUserId = adminId; resolutionCode = bounded(code, 64); resolvedAt = time(at);
    }
    private static Instant time(Instant value) { return Objects.requireNonNull(value).truncatedTo(ChronoUnit.MICROS); }
    private static String bounded(String value, int max) { String v = Objects.requireNonNull(value).strip(); if (v.isEmpty() || v.length() > max) throw new IllegalArgumentException("Invalid bounded text"); return v; }
    private static String sha(String value) { String v = Objects.requireNonNull(value).toLowerCase(Locale.ROOT); if (!v.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("fingerprint"); return v; }
}
