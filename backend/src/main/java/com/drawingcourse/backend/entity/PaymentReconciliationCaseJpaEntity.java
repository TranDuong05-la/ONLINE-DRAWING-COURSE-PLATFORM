package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import java.time.Instant;
import java.util.Objects;

/** Bounded review record; callback secrets and raw query strings are never stored here. */
@Entity
@Table(name = "payment_reconciliation_cases", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_reconciliation_evidence",
                columnNames = {"provider", "event_reference", "case_type", "evidence_fingerprint"}),
        @UniqueConstraint(name = "uk_payment_reconciliation_conflict", columnNames = "payment_event_conflict_id")
}, indexes = {
        @Index(name = "ix_payment_reconciliation_status_received", columnList = "case_status,received_at"),
        @Index(name = "ix_payment_reconciliation_order_status", columnList = "order_id,case_status")
})
@Check(name = "ck_payment_reconciliation_type", constraints = "case_type IN ('UNKNOWN_MERCHANT_REFERENCE','LATE_SUCCESS','AMOUNT_MISMATCH','CURRENCY_MISMATCH','CONFLICTING_CALLBACK','INVALID_PROVIDER_TIMESTAMP','UNKNOWN_PAYMENT_STATUS','ORDER_NOT_PAYABLE','SETTLEMENT_CONFLICT','UNUSABLE_TRANSACTION_REFERENCE')")
@Check(name = "ck_payment_reconciliation_timestamp_state", constraints = "provider_timestamp_state IS NULL OR provider_timestamp_state IN ('VALID','MISSING','MALFORMED','OUT_OF_RANGE')")
@Check(name = "ck_payment_reconciliation_timestamp_value", constraints = "(provider_timestamp_state IS NULL AND provider_paid_at IS NULL) OR (provider_timestamp_state IS NOT NULL AND provider_timestamp_state IN ('VALID','OUT_OF_RANGE') AND provider_paid_at IS NOT NULL) OR (provider_timestamp_state IS NOT NULL AND provider_timestamp_state IN ('MISSING','MALFORMED') AND provider_paid_at IS NULL)")
@Check(name = "ck_payment_reconciliation_outcome", constraints = "provider_outcome IS NULL OR provider_outcome IN ('SUCCEEDED','FAILED','UNKNOWN')")
@Check(name = "ck_payment_reconciliation_status", constraints = "case_status IN ('OPEN','RESOLVED','DISMISSED')")
@Check(name = "ck_payment_reconciliation_resolution", constraints = "(case_status = 'OPEN' AND resolution_code IS NULL AND resolved_at IS NULL) OR (case_status IN ('RESOLVED','DISMISSED') AND resolution_code IS NOT NULL AND resolved_at IS NOT NULL)")
@Check(name = "ck_payment_reconciliation_route", constraints = "((case_type = 'UNKNOWN_MERCHANT_REFERENCE' AND payment_event_id IS NULL AND payment_event_conflict_id IS NULL AND payment_attempt_id IS NULL AND order_id IS NULL AND merchant_reference IS NOT NULL AND amount_minor_units IS NOT NULL AND provider_outcome IS NOT NULL AND payload_sha256 IS NOT NULL AND provider_timestamp_state IS NOT NULL) OR (case_type = 'UNUSABLE_TRANSACTION_REFERENCE' AND payment_event_id IS NULL AND payment_event_conflict_id IS NULL AND payment_attempt_id IS NOT NULL AND order_id IS NOT NULL AND merchant_reference IS NOT NULL AND amount_minor_units IS NOT NULL AND provider_outcome IS NOT NULL AND payload_sha256 IS NOT NULL AND provider_timestamp_state IS NOT NULL) OR (case_type NOT IN ('UNKNOWN_MERCHANT_REFERENCE','UNUSABLE_TRANSACTION_REFERENCE') AND payment_event_id IS NOT NULL AND payment_attempt_id IS NOT NULL AND order_id IS NOT NULL AND merchant_reference IS NULL AND provider_outcome IS NULL AND payload_sha256 IS NULL AND provider_timestamp_state IS NULL AND ((case_type = 'AMOUNT_MISMATCH' AND amount_minor_units IS NOT NULL) OR (case_type <> 'AMOUNT_MISMATCH' AND amount_minor_units IS NULL)) AND ((case_type = 'CONFLICTING_CALLBACK' AND payment_event_conflict_id IS NOT NULL) OR (case_type <> 'CONFLICTING_CALLBACK' AND payment_event_conflict_id IS NULL))))")
@Check(name = "ck_payment_reconciliation_amount", constraints = "amount_minor_units IS NULL OR amount_minor_units >= 0")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentReconciliationCaseJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, updatable = false, length = 64)
    private String provider;

    @Column(name = "event_reference", nullable = false, updatable = false, length = 255)
    private String eventReference;

    @Column(name = "case_type", nullable = false, updatable = false, length = 32)
    private String caseType;

    @Column(name = "evidence_fingerprint", nullable = false, updatable = false, length = 67)
    private String evidenceFingerprint;

    @Column(name = "payment_event_id")
    private Integer paymentEventId;

    @Column(name = "payment_event_conflict_id")
    private Integer paymentEventConflictId;

    @Column(name = "payment_attempt_id")
    private Integer paymentAttemptId;

    @Column(name = "order_id")
    private Integer orderId;

    @Column(name = "merchant_reference", length = 128)
    private String merchantReference;

    @Column(name = "amount_minor_units")
    private Long amountMinorUnits;

    @Column(name = "provider_outcome", length = 16)
    private String providerOutcome;

    @Column(name = "provider_timestamp_state", length = 16)
    private String providerTimestampState;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "provider_paid_at")
    private Instant providerPaidAt;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "payload_sha256", length = 64, columnDefinition = "CHAR(64)")
    private String payloadSha256;

    @Column(name = "case_status", nullable = false, length = 16)
    private String caseStatus = "OPEN";

    @Column(name = "resolution_code", length = 32)
    private String resolutionCode;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_attempt_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_payment_reconciliation_attempt"))
    private PaymentAttemptJpaEntity attemptReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_payment_reconciliation_order"))
    private OrderJpaEntity orderReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_event_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_payment_reconciliation_event"))
    private PaymentEventJpaEntity eventReferenceRow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_event_conflict_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_payment_reconciliation_conflict"))
    private PaymentEventConflictJpaEntity conflictReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "payment_event_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "payment_attempt_id", referencedColumnName = "attempt_id", insertable = false, updatable = false),
            @JoinColumn(name = "order_id", referencedColumnName = "order_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_payment_reconciliation_event_route"))
    private PaymentEventJpaEntity eventRouteReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "payment_event_conflict_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "payment_event_id", referencedColumnName = "first_event_id", insertable = false, updatable = false),
            @JoinColumn(name = "payment_attempt_id", referencedColumnName = "attempt_id", insertable = false, updatable = false),
            @JoinColumn(name = "order_id", referencedColumnName = "order_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_payment_reconciliation_conflict_route"))
    private PaymentEventConflictJpaEntity conflictRouteReference;

    public static PaymentReconciliationCaseJpaEntity unknownMerchant(String provider, String eventReference,
            String caseType, String evidenceFingerprint, String merchantReference, long amountMinorUnits,
            String providerOutcome, String timestampState, Instant providerPaidAt, Instant receivedAt, String payloadSha256) {
        var row = base(provider, eventReference, caseType, evidenceFingerprint, receivedAt);
        row.merchantReference = Objects.requireNonNull(merchantReference);
        row.amountMinorUnits = amountMinorUnits;
        row.providerOutcome = Objects.requireNonNull(providerOutcome);
        row.providerTimestampState = Objects.requireNonNull(timestampState);
        row.providerPaidAt = providerPaidAt;
        row.payloadSha256 = Objects.requireNonNull(payloadSha256);
        return row;
    }

    public static PaymentReconciliationCaseJpaEntity unusableReference(String provider, String eventReference,
            String caseType, String evidenceFingerprint, Integer attemptId, Integer orderId, String merchantReference,
            long amountMinorUnits, String providerOutcome, String timestampState, Instant providerPaidAt,
            Instant receivedAt, String payloadSha256) {
        var row = unknownMerchant(provider, eventReference, caseType, evidenceFingerprint, merchantReference,
                amountMinorUnits, providerOutcome, timestampState, providerPaidAt, receivedAt, payloadSha256);
        row.paymentAttemptId = Objects.requireNonNull(attemptId);
        row.orderId = Objects.requireNonNull(orderId);
        return row;
    }

    public static PaymentReconciliationCaseJpaEntity linked(String provider, String eventReference, String caseType,
            String evidenceFingerprint, Integer eventId, Integer conflictId, Integer attemptId, Integer orderId,
            Long amountMinorUnits, Instant receivedAt) {
        var row = base(provider, eventReference, caseType, evidenceFingerprint, receivedAt);
        row.paymentEventId = Objects.requireNonNull(eventId);
        row.paymentEventConflictId = conflictId;
        row.paymentAttemptId = Objects.requireNonNull(attemptId);
        row.orderId = Objects.requireNonNull(orderId);
        row.amountMinorUnits = amountMinorUnits;
        return row;
    }

    public void resolve(String code, Instant at) {
        if (!"OPEN".equals(caseStatus) || code == null || code.isBlank() || at == null)
            throw new IllegalStateException("Only an open reconciliation case can be resolved");
        caseStatus = "RESOLVED";
        resolutionCode = code;
        resolvedAt = at;
    }

    private static PaymentReconciliationCaseJpaEntity base(String provider, String eventReference, String caseType,
            String evidenceFingerprint, Instant receivedAt) {
        var row = new PaymentReconciliationCaseJpaEntity();
        row.provider = Objects.requireNonNull(provider);
        row.eventReference = Objects.requireNonNull(eventReference);
        row.caseType = Objects.requireNonNull(caseType);
        row.evidenceFingerprint = Objects.requireNonNull(evidenceFingerprint);
        row.receivedAt = Objects.requireNonNull(receivedAt);
        return row;
    }
}
