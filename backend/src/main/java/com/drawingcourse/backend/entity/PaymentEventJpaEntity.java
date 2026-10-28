package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Append-only event/decision snapshot. No setters, merge/update API or cascade deletion. */
@Entity @Table(name="payment_events",uniqueConstraints={
        @UniqueConstraint(name="uk_payment_event",columnNames={"provider","event_reference"}),
        @UniqueConstraint(name="uk_payment_event_id_attempt_order",columnNames={"id","attempt_id","order_id"})},
        indexes=@Index(name="ix_payment_event_attempt_owner",columnList="attempt_id,user_id"))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PaymentEventJpaEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Integer id;
    @Column(name="attempt_id",nullable=false,updatable=false) private Integer attemptId;
    @Column(name="order_id",nullable=false,updatable=false) private Integer orderId;
    @Column(name="user_id",nullable=false,updatable=false) private Integer userId;
    @Column(nullable=false,updatable=false,length=64) private String provider;
    @Column(name="event_reference",nullable=false,updatable=false,length=255) private String eventReference;
    @Column(name="merchant_reference",nullable=false,updatable=false,length=128) private String merchantReference;
    @Column(name="transaction_reference",updatable=false,length=255) private String transactionReference;
    @Column(name="amount_vnd",nullable=false,updatable=false) private long amountVnd;
    @Column(nullable=false,updatable=false,length=16) private String currency;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,updatable=false,length=24) private PaymentEvent.Outcome outcome;
    @Convert(converter=PaymentUtcInstantConverter.class) @Column(name="occurred_at",nullable=false,updatable=false) private Instant occurredAt;
    @Convert(converter=PaymentUtcInstantConverter.class) @Column(name="received_at",nullable=false,updatable=false) private Instant receivedAt;
    @Column(name="payload_sha256",nullable=false,updatable=false,length=64) private String payloadSha256;
    @Column(name="verification_reference",nullable=false,updatable=false,length=255) private String verificationReference;
    @Column(nullable=false,updatable=false,length=67) private String fingerprint;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="before_status",nullable=false,updatable=false,length=24) private PaymentStatus beforeStatus;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="after_status",nullable=false,updatable=false,length=24) private PaymentStatus afterStatus;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,updatable=false,length=32) private PaymentEventAudit.Disposition disposition;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,updatable=false,length=32) private PaymentAttempt.ReviewReason reason;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="observed_order_status",nullable=false,updatable=false,length=24) private OrderStatus observedOrderStatus;

    public static PaymentEventJpaEntity newSnapshot(PaymentEventAudit audit) {
        if(audit.id()!=null) throw new IllegalArgumentException("Do not reinsert an existing event");
        var row=new PaymentEventJpaEntity(); var event=audit.event();
        row.attemptId=audit.attemptId(); row.orderId=audit.orderId(); row.userId=audit.userId(); row.provider=event.provider();
        row.eventReference=event.eventReference(); row.merchantReference=event.merchantReference(); row.transactionReference=event.transactionReference();
        row.amountVnd=event.amountVnd(); row.currency=event.currency(); row.outcome=event.outcome(); row.occurredAt=event.occurredAt(); row.receivedAt=event.receivedAt();
        row.payloadSha256=event.payloadSha256(); row.verificationReference=event.verificationReference(); row.fingerprint=audit.fingerprint();
        row.beforeStatus=audit.beforeStatus(); row.afterStatus=audit.afterStatus(); row.disposition=audit.disposition(); row.reason=audit.reason();
        row.observedOrderStatus=audit.observedOrderStatus(); return row;
    }
}
