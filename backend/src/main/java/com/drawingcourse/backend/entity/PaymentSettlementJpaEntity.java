package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.Objects;

/** Immutable record of one committed successful payment settlement. */
@Entity
@Table(name = "payment_settlements", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_settlement_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_settlement_attempt", columnNames = "payment_attempt_id"),
        @UniqueConstraint(name = "uk_payment_settlement_event", columnNames = "payment_event_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentSettlementJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Integer orderId;

    @Column(name = "payment_attempt_id", nullable = false, updatable = false)
    private Integer paymentAttemptId;

    @Column(name = "payment_event_id", nullable = false, updatable = false)
    private Integer paymentEventId;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "provider_paid_at", nullable = false, updatable = false)
    private Instant providerPaidAt;

    @Convert(converter = PaymentUtcInstantConverter.class)
    @Column(name = "settled_at", nullable = false, updatable = false)
    private Instant settledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_payment_settlement_order"))
    private OrderJpaEntity orderReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "payment_attempt_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "order_id", referencedColumnName = "order_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_payment_settlement_attempt_order"))
    private PaymentAttemptJpaEntity attemptReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "payment_event_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "payment_attempt_id", referencedColumnName = "attempt_id", insertable = false, updatable = false),
            @JoinColumn(name = "order_id", referencedColumnName = "order_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_payment_settlement_event_route"))
    private PaymentEventJpaEntity eventReference;

    public static PaymentSettlementJpaEntity record(Integer orderId, Integer paymentAttemptId, Integer paymentEventId,
                                                    Instant providerPaidAt, Instant settledAt) {
        var row = new PaymentSettlementJpaEntity();
        row.orderId = Objects.requireNonNull(orderId);
        row.paymentAttemptId = Objects.requireNonNull(paymentAttemptId);
        row.paymentEventId = Objects.requireNonNull(paymentEventId);
        row.providerPaidAt = Objects.requireNonNull(providerPaidAt);
        row.settledAt = Objects.requireNonNull(settledAt);
        return row;
    }
}
