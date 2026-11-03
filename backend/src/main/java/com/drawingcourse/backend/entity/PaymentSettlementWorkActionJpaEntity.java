package com.drawingcourse.backend.entity;

import com.artacademy.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** Immutable audit snapshot for a future authorized operator work-item action. */
@Entity
@Table(name = "payment_settlement_work_actions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_settlement_work_action_key", columnNames = {"work_item_id", "action_key"})
}, indexes = @Index(name = "ix_payment_settlement_work_action_history", columnList = "work_item_id,id"))
@Check(name = "ck_payment_settlement_work_action", constraints = "((action_type = 'REQUEUE' AND from_state = 'MANUAL_REVIEW' AND to_state = 'READY' AND reason_code IN ('TRANSIENT_CAUSE_RESOLVED','ADAPTER_RESTORED','EVIDENCE_REVERIFIED')) OR (action_type = 'CLOSE_NOT_ELIGIBLE' AND from_state = 'MANUAL_REVIEW' AND to_state = 'CLOSED_NOT_ELIGIBLE' AND reason_code = 'REVIEWED_NOT_ELIGIBLE'))")
@Check(name = "ck_payment_settlement_work_action_attempts", constraints = "prior_attempt_count BETWEEN 0 AND 8")
@Check(name = "ck_payment_settlement_work_action_generation", constraints = "prior_retry_generation >= 0")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentSettlementWorkActionJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "work_item_id", nullable = false, updatable = false)
    private Integer workItemId;

    @Column(name = "action_key", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String actionKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "action_type", nullable = false, updatable = false, length = 24)
    private PaymentSettlementWorkActionType actionType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "from_state", nullable = false, updatable = false, length = 24)
    private PaymentSettlementWorkState fromState;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "to_state", nullable = false, updatable = false, length = 24)
    private PaymentSettlementWorkState toState;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Integer actorUserId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reason_code", nullable = false, updatable = false, length = 32)
    private PaymentSettlementWorkActionReason reasonCode;

    @Column(name = "prior_attempt_count", nullable = false, updatable = false)
    private Short priorAttemptCount;

    @Column(name = "prior_retry_generation", nullable = false, updatable = false)
    private Long priorRetryGeneration;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", referencedColumnName = "id", insertable = false,
            updatable = false, foreignKey = @ForeignKey(name = "fk_payment_settlement_work_action_item"))
    private PaymentSettlementWorkItemJpaEntity workItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", referencedColumnName = "id", insertable = false,
            updatable = false, foreignKey = @ForeignKey(name = "fk_payment_settlement_work_action_actor"))
    private User actor;

    public static PaymentSettlementWorkActionJpaEntity snapshot(Integer workItemId, String actionKey,
            PaymentSettlementWorkActionType type, PaymentSettlementWorkState from, PaymentSettlementWorkState to,
            Integer actorUserId, PaymentSettlementWorkActionReason reason, short priorAttemptCount,
            long priorRetryGeneration, Instant now) {
        if (workItemId == null || workItemId <= 0 || actorUserId == null || actorUserId <= 0)
            throw new IllegalArgumentException("work item and actor ids must be positive");
        if (actionKey == null || !isCanonicalUuid(actionKey))
            throw new IllegalArgumentException("actionKey must be a canonical UUID string");
        if (priorAttemptCount < 0 || priorAttemptCount > 8 || priorRetryGeneration < 0)
            throw new IllegalArgumentException("invalid prior retry counters");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");
        boolean requeue = type == PaymentSettlementWorkActionType.REQUEUE
                && from == PaymentSettlementWorkState.MANUAL_REVIEW
                && to == PaymentSettlementWorkState.READY
                && reason != PaymentSettlementWorkActionReason.REVIEWED_NOT_ELIGIBLE;
        boolean close = type == PaymentSettlementWorkActionType.CLOSE_NOT_ELIGIBLE
                && from == PaymentSettlementWorkState.MANUAL_REVIEW
                && to == PaymentSettlementWorkState.CLOSED_NOT_ELIGIBLE
                && reason == PaymentSettlementWorkActionReason.REVIEWED_NOT_ELIGIBLE;
        if (!requeue && !close) throw new IllegalArgumentException("unsupported operator action transition");

        var row = new PaymentSettlementWorkActionJpaEntity();
        row.workItemId = workItemId;
        row.actionKey = actionKey.toLowerCase(Locale.ROOT);
        row.actionType = type;
        row.fromState = from;
        row.toState = to;
        row.actorUserId = actorUserId;
        row.reasonCode = reason;
        row.priorAttemptCount = priorAttemptCount;
        row.priorRetryGeneration = priorRetryGeneration;
        row.createdAt = now;
        return row;
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return java.util.UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalidUuid) {
            return false;
        }
    }
}
