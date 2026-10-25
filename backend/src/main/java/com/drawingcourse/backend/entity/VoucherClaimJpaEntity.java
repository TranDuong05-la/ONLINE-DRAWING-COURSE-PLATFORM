package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name = "voucher_claims", uniqueConstraints = {
        @UniqueConstraint(name = "uk_voucher_claim_key", columnNames = "claim_key"),
        @UniqueConstraint(name = "uk_voucher_claim_order", columnNames = "order_id")},
        indexes = @Index(name = "ix_voucher_claim_expiry", columnList = "state,expires_at"))
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoucherClaimJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Integer id;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "claim_key", nullable = false, updatable = false, length = 128) private String claimKey;
    @Column(name = "voucher_id", nullable = false, updatable = false) private Integer voucherId;
    @Column(name = "definition_version", nullable = false, updatable = false) private long definitionVersion;
    @Column(name = "user_id", nullable = false, updatable = false) private Integer userId;
    @Column(name = "order_id", nullable = false, updatable = false) private Integer orderId;
    @Column(name = "code_snapshot", nullable = false, updatable = false, length = 255) private String codeSnapshot;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "kind_snapshot", nullable = false, updatable = false, length = 24) private OrderVoucher.Kind kindSnapshot;
    @Column(name = "value_snapshot", nullable = false, updatable = false) private long valueSnapshot;
    @Column(name = "cap_snapshot_vnd", nullable = false, updatable = false) private long capSnapshotVnd;
    @Column(name = "eligible_subtotal_vnd", nullable = false, updatable = false) private long eligibleSubtotalVnd;
    @Column(name = "discount_vnd", nullable = false, updatable = false) private long discountVnd;
    @Convert(converter = VoucherUtcInstantConverter.class) @Column(name = "order_created_at", nullable = false, updatable = false) private Instant orderCreatedAt;
    @Convert(converter = VoucherUtcInstantConverter.class) @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Convert(converter = VoucherUtcInstantConverter.class) @Column(name = "reserved_at", nullable = false, updatable = false) private Instant reservedAt;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16) private VoucherClaim.State state;
    @Convert(converter = VoucherUtcInstantConverter.class) @Column(name = "transitioned_at") private Instant transitionedAt;
    @Column(name = "payment_reference", columnDefinition = "TEXT") private String paymentReference;
    @Convert(converter = VoucherUtcInstantConverter.class) @Column(name = "paid_at") private Instant paidAt;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "release_reason", length = 24) private VoucherClaim.ReleaseReason releaseReason;

    /** Snapshot mapping. The application service owns transactional insertion and transitions. */
    public static VoucherClaimJpaEntity fromSnapshot(VoucherClaim claim) {
        var row = new VoucherClaimJpaEntity();
        row.claimKey = claim.claimKey(); row.voucherId = claim.voucherId(); row.definitionVersion = claim.definitionVersion();
        row.userId = claim.userId(); row.orderId = claim.orderId(); row.codeSnapshot = claim.terms().code();
        row.kindSnapshot = claim.terms().kind(); row.valueSnapshot = claim.terms().value(); row.capSnapshotVnd = claim.terms().maximumDiscountVnd();
        row.eligibleSubtotalVnd = claim.eligibleSubtotalVnd(); row.discountVnd = claim.discountVnd();
        row.orderCreatedAt = claim.orderCreatedAt(); row.expiresAt = claim.expiresAt(); row.reservedAt = claim.reservedAt();
        row.state = claim.state(); row.transitionedAt = claim.transitionedAt(); row.paymentReference = claim.paymentReference();
        row.paidAt = claim.paidAt(); row.releaseReason = claim.releaseReason(); return row;
    }

    public void redeem(String reference, Instant paidAt, Instant observedAt) {
        apply(claim().redeem(reference, paidAt, observedAt));
    }
    public void release(VoucherClaim.ReleaseReason reason, Instant at) {
        apply(claim().releaseUnpaid(reason, at));
    }
    private VoucherClaim claim() {
        return new VoucherClaim(claimKey, voucherId, definitionVersion, userId, orderId,
                new OrderVoucher(codeSnapshot, kindSnapshot, valueSnapshot, capSnapshotVnd), eligibleSubtotalVnd,
                discountVnd, orderCreatedAt, expiresAt, reservedAt, state, transitionedAt, paymentReference, paidAt, releaseReason);
    }
    private void apply(VoucherClaim updated) {
        if (!claimKey.equals(updated.claimKey()) || !voucherId.equals(updated.voucherId()) || !orderId.equals(updated.orderId())
                || !userId.equals(updated.userId()) || definitionVersion != updated.definitionVersion()
                || !codeSnapshot.equals(updated.terms().code()) || kindSnapshot != updated.terms().kind()
                || valueSnapshot != updated.terms().value() || capSnapshotVnd != updated.terms().maximumDiscountVnd()
                || eligibleSubtotalVnd != updated.eligibleSubtotalVnd() || discountVnd != updated.discountVnd()
                || !orderCreatedAt.equals(updated.orderCreatedAt()) || !expiresAt.equals(updated.expiresAt())
                || !reservedAt.equals(updated.reservedAt())) throw new IllegalArgumentException("Claim snapshot identity is immutable");
        state = updated.state(); transitionedAt = updated.transitionedAt(); paymentReference = updated.paymentReference();
        paidAt = updated.paidAt(); releaseReason = updated.releaseReason();
    }
}
