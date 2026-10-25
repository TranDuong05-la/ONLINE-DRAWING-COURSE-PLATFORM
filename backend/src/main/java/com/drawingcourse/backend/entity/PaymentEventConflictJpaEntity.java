package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Objects;

/** Append-only, privacy-minimal evidence for a signature-verified event identity conflict. */
@Entity @Table(name="payment_event_conflicts",uniqueConstraints=
        @UniqueConstraint(name="uk_payment_event_conflict",columnNames={"first_event_id","conflicting_fingerprint"}),
        indexes=@Index(name="ix_payment_event_conflict_owner",columnList="user_id,order_id"))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PaymentEventConflictJpaEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Integer id;
    @Column(name="first_event_id",nullable=false,updatable=false) private Integer firstEventId;
    @Column(name="attempt_id",nullable=false,updatable=false) private Integer attemptId;
    @Column(name="order_id",nullable=false,updatable=false) private Integer orderId;
    @Column(name="user_id",nullable=false,updatable=false) private Integer userId;
    @Column(nullable=false,updatable=false,length=64) private String provider;
    @Column(name="event_reference",nullable=false,updatable=false,length=255) private String eventReference;
    @Column(name="first_fingerprint",nullable=false,updatable=false,length=67) private String firstFingerprint;
    @Column(name="conflicting_fingerprint",nullable=false,updatable=false,length=67) private String conflictingFingerprint;
    @Column(name="merchant_reference",nullable=false,updatable=false,length=128) private String merchantReference;
    @Column(name="transaction_reference",updatable=false,length=255) private String transactionReference;
    @Column(name="amount_vnd",nullable=false,updatable=false) private long amountVnd;
    @Column(nullable=false,updatable=false,length=16) private String currency;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,updatable=false,length=24) private PaymentEvent.Outcome outcome;
    @Convert(converter=PaymentUtcInstantConverter.class) @Column(name="occurred_at",nullable=false,updatable=false) private Instant occurredAt;
    @Convert(converter=PaymentUtcInstantConverter.class) @Column(name="received_at",nullable=false,updatable=false) private Instant receivedAt;
    @Column(name="payload_sha256",nullable=false,updatable=false,length=64) private String payloadSha256;
    @Column(name="verification_reference",nullable=false,updatable=false,length=255) private String verificationReference;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="observed_order_status",nullable=false,updatable=false,length=24) private OrderStatus observedOrderStatus;

    public static PaymentEventConflictJpaEntity verifiedConflict(PaymentEventAudit first,PaymentEvent conflicting,
                                                                  PaymentAttempt routedAttempt,OrderStatus observedStatus) {
        Objects.requireNonNull(first); Objects.requireNonNull(conflicting); Objects.requireNonNull(routedAttempt); Objects.requireNonNull(observedStatus);
        if(!first.event().provider().equals(conflicting.provider()) || !first.event().eventReference().equals(conflicting.eventReference())
                || first.fingerprint().equals(conflicting.fingerprint())) throw new IllegalArgumentException("Not a conflicting payment event identity");
        var row=new PaymentEventConflictJpaEntity(); row.firstEventId=first.id(); row.attemptId=routedAttempt.id();
        row.orderId=routedAttempt.orderId(); row.userId=routedAttempt.userId(); row.provider=conflicting.provider(); row.eventReference=conflicting.eventReference();
        row.firstFingerprint=first.fingerprint(); row.conflictingFingerprint=conflicting.fingerprint(); row.merchantReference=conflicting.merchantReference();
        row.transactionReference=conflicting.transactionReference(); row.amountVnd=conflicting.amountVnd(); row.currency=conflicting.currency();
        row.outcome=conflicting.outcome(); row.occurredAt=conflicting.occurredAt(); row.receivedAt=conflicting.receivedAt();
        row.payloadSha256=conflicting.payloadSha256(); row.verificationReference=conflicting.verificationReference();
        row.observedOrderStatus=observedStatus; return row;
    }
}
