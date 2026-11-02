package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/** V9 durable work identity and lease snapshot; does not perform or claim settlement work. */
@Entity
@Table(name = "payment_settlement_work_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_settlement_work_event", columnNames = "payment_event_id")
}, indexes = {
        @Index(name = "ix_payment_settlement_work_due", columnList = "state,available_at,id"),
        @Index(name = "ix_payment_settlement_work_lease", columnList = "state,lease_until,id"),
        @Index(name = "ix_payment_settlement_work_review", columnList = "state,updated_at,id")
})
@Check(name = "ck_payment_settlement_work_state", constraints = "state IN ('READY','RETRY_WAIT','LEASED','COMPLETED','MANUAL_REVIEW','CLOSED_NOT_ELIGIBLE')")
@Check(name = "ck_payment_settlement_work_attempts", constraints = "attempt_count BETWEEN 0 AND 8")
@Check(name = "ck_payment_settlement_work_generation", constraints = "retry_generation >= 0")
@Check(name = "ck_payment_settlement_work_failure", constraints = "last_failure_code IS NULL OR last_failure_code IN ('TRANSIENT_DATABASE','DEADLOCK','LOCK_TIMEOUT','LEASE_EXPIRED','ATTEMPTS_EXHAUSTED','EVENT_NOT_ELIGIBLE','ORDER_NOT_ELIGIBLE','SETTLEMENT_CONFLICT','PARTICIPANT_REJECTED','UNCLASSIFIED_FAILURE')")
@Check(name = "ck_payment_settlement_work_fields", constraints = "((state IN ('READY','RETRY_WAIT') AND available_at IS NOT NULL AND lease_token IS NULL AND lease_owner IS NULL AND lease_until IS NULL AND completed_at IS NULL) OR (state = 'LEASED' AND available_at IS NULL AND lease_token IS NOT NULL AND lease_owner IS NOT NULL AND lease_until IS NOT NULL AND completed_at IS NULL) OR (state IN ('MANUAL_REVIEW','CLOSED_NOT_ELIGIBLE') AND available_at IS NULL AND lease_token IS NULL AND lease_owner IS NULL AND lease_until IS NULL AND completed_at IS NULL) OR (state = 'COMPLETED' AND available_at IS NULL AND lease_token IS NULL AND lease_owner IS NULL AND lease_until IS NULL AND completed_at IS NOT NULL))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentSettlementWorkItemJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "payment_event_id", nullable = false, updatable = false)
    private Integer paymentEventId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 24)
    private PaymentSettlementWorkState state;

    @Column(name = "attempt_count", nullable = false)
    private Short attemptCount;

    @Column(name = "retry_generation", nullable = false)
    private Long retryGeneration;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "available_at")
    private Instant availableAt;

    @Column(name = "lease_token", length = 36, columnDefinition = "CHAR(36)")
    private String leaseToken;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "last_claimed_at")
    private Instant lastClaimedAt;

    @Column(name = "last_failure_code", length = 32)
    private String lastFailureCode;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "completed_at")
    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_event_id", referencedColumnName = "id", insertable = false,
            updatable = false, foreignKey = @ForeignKey(name = "fk_payment_settlement_work_event"))
    private PaymentEventJpaEntity paymentEvent;

    /** The only initial-state factory; lease/state mutation is intentionally absent in this phase. */
    public static PaymentSettlementWorkItemJpaEntity ready(Integer eventId, Instant now) {
        if (eventId == null || eventId <= 0) throw new IllegalArgumentException("eventId must be positive");
        Objects.requireNonNull(now, "now");
        var row = new PaymentSettlementWorkItemJpaEntity();
        row.paymentEventId = eventId;
        row.state = PaymentSettlementWorkState.READY;
        row.attemptCount = 0;
        row.retryGeneration = 0L;
        row.availableAt = now;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    /** Guard used by a future audited requeue operation; caller must update atomically in its transaction. */
    public static long nextRetryGeneration(long current) {
        if (current < 0) throw new IllegalArgumentException("retry generation must be nonnegative");
        if (current == Long.MAX_VALUE) throw new IllegalStateException("retry generation is exhausted");
        return current + 1;
    }
}
