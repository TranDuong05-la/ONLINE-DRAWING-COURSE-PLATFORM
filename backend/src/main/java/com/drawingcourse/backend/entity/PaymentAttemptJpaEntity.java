package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name="payment_attempts",uniqueConstraints={
        @UniqueConstraint(name="uk_payment_merchant",columnNames={"provider","merchant_reference"}),
        @UniqueConstraint(name="uk_payment_transaction",columnNames={"provider","transaction_reference"})},
        indexes=@Index(name="ix_payment_order_owner",columnList="order_id,user_id"))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PaymentAttemptJpaEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Integer id;
    @Version @Column(nullable=false) private Long version;
    @Column(name="order_id",nullable=false,updatable=false) private Integer orderId;
    @Column(name="user_id",nullable=false,updatable=false) private Integer userId;
    @Column(nullable=false,updatable=false,length=64) private String provider;
    @Column(name="merchant_reference",nullable=false,updatable=false,length=128) private String merchantReference;
    @Column(name="expected_amount_vnd",nullable=false,updatable=false) private long expectedAmountVnd;
    @Column(nullable=false,updatable=false,length=3) private String currency;
    @Convert(converter=PaymentUtcInstantConverter.class) @Column(name="order_created_at",nullable=false,updatable=false) private Instant orderCreatedAt;
    @Convert(converter=PaymentUtcInstantConverter.class) @Column(name="expires_at",nullable=false,updatable=false) private Instant expiresAt;
    @Convert(converter=PaymentUtcInstantConverter.class) @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Convert(converter=PaymentUtcInstantConverter.class) @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=24) private PaymentStatus status;
    @Column(name="transaction_reference",length=255) private String transactionReference;
    @Convert(converter=PaymentUtcInstantConverter.class) @Column(name="succeeded_at") private Instant succeededAt;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="review_reason",nullable=false,length=32) private PaymentAttempt.ReviewReason reviewReason;

    public static PaymentAttemptJpaEntity newSnapshot(PaymentAttempt value) {
        if(value.id()!=null) throw new IllegalArgumentException("Do not insert an existing attempt as a new row");
        var row=new PaymentAttemptJpaEntity();
        row.orderId=value.orderId(); row.userId=value.userId(); row.provider=value.provider(); row.merchantReference=value.merchantReference();
        row.expectedAmountVnd=value.expectedAmountVnd(); row.currency=value.currency(); row.orderCreatedAt=value.orderCreatedAt();
        row.expiresAt=value.expiresAt(); row.createdAt=value.createdAt(); row.updatedAt=value.updatedAt(); row.status=value.status();
        row.transactionReference=value.transactionReference(); row.succeededAt=value.succeededAt(); row.reviewReason=value.reviewReason(); return row;
    }

    /** Apply a policy decision while preserving every immutable routing/money/deadline field. */
    public void applyDecision(PaymentAttempt value) {
        if (!java.util.Objects.equals(id,value.id()) || !java.util.Objects.equals(orderId,value.orderId())
                || !java.util.Objects.equals(userId,value.userId()) || !java.util.Objects.equals(provider,value.provider())
                || !java.util.Objects.equals(merchantReference,value.merchantReference())
                || expectedAmountVnd!=value.expectedAmountVnd() || !java.util.Objects.equals(currency,value.currency())
                || !java.util.Objects.equals(orderCreatedAt,value.orderCreatedAt()) || !java.util.Objects.equals(expiresAt,value.expiresAt())
                || !java.util.Objects.equals(createdAt,value.createdAt()))
            throw new IllegalArgumentException("Payment attempt identity or immutable snapshot changed");
        updatedAt=value.updatedAt(); status=value.status(); transactionReference=value.transactionReference();
        succeededAt=value.succeededAt(); reviewReason=value.reviewReason();
    }
}
