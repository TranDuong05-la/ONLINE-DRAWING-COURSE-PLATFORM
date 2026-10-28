package com.drawingcourse.backend.entity;

import com.artacademy.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "checkout_requests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_checkout_requests_user_key", columnNames = {"user_id", "idempotency_key"}),
        @UniqueConstraint(name = "uk_checkout_requests_order", columnNames = "order_id")
}, indexes = @Index(name = "ix_checkout_requests_state_updated", columnList = "state,updated_at"))
@Check(name = "ck_checkout_requests_completion", constraints =
        "(state = 'PROCESSING' AND order_id IS NULL AND completed_at IS NULL) OR " +
        "(state = 'COMPLETED' AND order_id IS NOT NULL AND completed_at IS NOT NULL)")
@Check(name = "ck_checkout_requests_state", constraints = "state IN ('PROCESSING', 'COMPLETED')")
public class CheckoutRequestJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_checkout_requests_user"))
    private User user;

    @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 67, updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private CheckoutRequestState state;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", foreignKey = @ForeignKey(name = "fk_checkout_requests_order"))
    private OrderJpaEntity order;

    @Convert(converter = OrderUtcInstantConverter.class)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Convert(converter = OrderUtcInstantConverter.class)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Convert(converter = OrderUtcInstantConverter.class)
    @Column(name = "completed_at")
    private Instant completedAt;

    protected CheckoutRequestJpaEntity() {}

    public CheckoutRequestJpaEntity(User user, String key, String fingerprint, Instant now) {
        this.user = Objects.requireNonNull(user, "user");
        this.idempotencyKey = Objects.requireNonNull(key, "key");
        this.requestFingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.state = CheckoutRequestState.PROCESSING;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void complete(OrderJpaEntity completedOrder, Instant at) {
        Objects.requireNonNull(completedOrder, "completedOrder");
        Objects.requireNonNull(at, "at");
        if (state == CheckoutRequestState.COMPLETED) {
            if (!order.getId().equals(completedOrder.getId()))
                throw new IllegalStateException("Checkout request is already linked to another Order");
            return;
        }
        if (state != CheckoutRequestState.PROCESSING) throw new IllegalStateException("Invalid Checkout request state");
        state = CheckoutRequestState.COMPLETED;
        order = completedOrder;
        updatedAt = at;
        completedAt = at;
    }

    public Integer getId() { return id; }
    public Integer getUserId() { return user.getId(); }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public CheckoutRequestState getState() { return state; }
    public Integer getOrderId() { return order == null ? null : order.getId(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
