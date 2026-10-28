package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "ix_orders_owner", columnList = "user_id,id"),
        @Index(name = "ix_orders_expiry", columnList = "status,payment_expires_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderJpaEntity {
    private static final OrderPersistenceMapper CONFIRMATION_MAPPER = new OrderPersistenceMapper();
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Version @Column(nullable = false)
    private Long version;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Integer userId;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 24)
    private OrderStatus status;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "payment_status", nullable = false, length = 24)
    private PaymentStatus paymentStatus;
    @Column(name = "merchandise_subtotal_vnd", nullable = false, updatable = false)
    private long merchandiseSubtotalVnd;
    @Column(name = "eligible_subtotal_vnd", nullable = false, updatable = false)
    private long eligibleSubtotalVnd;
    @Column(name = "discount_vnd", nullable = false, updatable = false)
    private long discountVnd;
    @Column(name = "shipping_fee_vnd", nullable = false, updatable = false)
    private long shippingFeeVnd;
    @Column(name = "total_vnd", nullable = false, updatable = false)
    private long totalVnd;
    @Column(name = "voucher_code", length = 255, updatable = false)
    private String voucherCode;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "voucher_kind", length = 24, updatable = false)
    private OrderVoucher.Kind voucherKind;
    @Column(name = "voucher_value", updatable = false)
    private Long voucherValue;
    @Column(name = "voucher_max_discount_vnd", updatable = false)
    private Long voucherMaxDiscountVnd;
    @Column(name = "payment_expires_at", updatable = false)
    @Convert(converter = OrderUtcInstantConverter.class)
    private Instant paymentExpiresAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    @Convert(converter = OrderUtcInstantConverter.class)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    @Convert(converter = OrderUtcInstantConverter.class)
    private Instant updatedAt;
    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("linePosition ASC")
    private List<OrderItemJpaEntity> items = new ArrayList<>();

    public static OrderJpaEntity fromSnapshot(Order domain) {
        var row = new OrderJpaEntity();
        row.userId = domain.getUserId();
        row.currency = domain.getCurrency();
        row.status = domain.getStatus();
        row.paymentStatus = domain.getPaymentStatus();
        row.merchandiseSubtotalVnd = domain.getMerchandiseSubtotalVnd();
        row.eligibleSubtotalVnd = domain.getEligibleSubtotalVnd();
        row.discountVnd = domain.getDiscountVnd();
        row.shippingFeeVnd = domain.getShippingFeeVnd();
        row.totalVnd = domain.getTotalVnd();
        domain.getVoucher().ifPresent(voucher -> {
            row.voucherCode = voucher.code();
            row.voucherKind = voucher.kind();
            row.voucherValue = voucher.value();
            row.voucherMaxDiscountVnd = voucher.maximumDiscountVnd();
        });
        row.createdAt = domain.getCreatedAt();
        row.paymentExpiresAt = domain.getExpiresAt();
        row.updatedAt = row.createdAt;
        int position = 0;
        for (var item : domain.getItems()) row.items.add(new OrderItemJpaEntity(row, position++, item));
        return row;
    }

    public List<OrderItemJpaEntity> getItems() { return Collections.unmodifiableList(items); }

    /** Apply the guarded domain transition to this managed row; caller owns the REQUIRED transaction and Order lock. */
    public void confirmPayment(PaymentAttempt attempt, PaymentEventAudit evidence) {
        if (id == null || attempt == null || !id.equals(attempt.orderId()))
            throw new IllegalStateException("Persisted Order and payment attempt do not match");
        var confirmed = CONFIRMATION_MAPPER.toDomain(this).confirmPayment(attempt, evidence);
        status = confirmed.getStatus();
        paymentStatus = confirmed.getPaymentStatus();
    }

    /** Transition only the mutable workflow fields; all historical money/items/deadline snapshots stay fixed. */
    public void expireUnpaid(Instant at) {
        Objects.requireNonNull(at);
        if (status != OrderStatus.PENDING_PAYMENT || paymentExpiresAt == null || at.isBefore(paymentExpiresAt)
                || paymentStatus == PaymentStatus.PENDING || paymentStatus == PaymentStatus.UNKNOWN
                || paymentStatus == PaymentStatus.SUCCEEDED)
            throw new IllegalStateException("Order is not eligible for unpaid expiration");
        status = OrderStatus.EXPIRED;
        paymentStatus = PaymentStatus.EXPIRED;
        updatedAt = at.truncatedTo(ChronoUnit.MICROS);
    }
    @PrePersist
    private void initializeTimestamps() {
        Objects.requireNonNull(createdAt, "createdAt");
        // Never replace creation/deadline at flush time or during a later payment attempt.
        if (updatedAt == null) updatedAt = createdAt;
    }
    @PreUpdate
    private void updateTimestamp() { updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
