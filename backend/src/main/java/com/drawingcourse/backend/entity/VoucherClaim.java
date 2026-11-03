package com.drawingcourse.backend.entity;

import java.time.*;
import java.util.Objects;

/** Immutable claim policy/snapshot, not an atomic database reservation or payment verifier. */
public record VoucherClaim(String claimKey, Integer voucherId, long definitionVersion, Integer userId,
                           Integer orderId, OrderVoucher terms, long eligibleSubtotalVnd, long discountVnd,
                           Instant orderCreatedAt, Instant expiresAt, Instant reservedAt, State state,
                           Instant transitionedAt, String paymentReference, Instant paidAt, ReleaseReason releaseReason) {
    public enum State { RESERVED, REDEEMED, RELEASED }
    public enum ReleaseReason { CANCELLED, EXPIRED, FINAL_FAILURE }
    public VoucherClaim {
        Objects.requireNonNull(claimKey); Objects.requireNonNull(voucherId); Objects.requireNonNull(userId);
        Objects.requireNonNull(orderId); Objects.requireNonNull(terms); Objects.requireNonNull(orderCreatedAt);
        Objects.requireNonNull(expiresAt); Objects.requireNonNull(reservedAt); Objects.requireNonNull(state);
        if (!claimKey.matches("[A-Za-z0-9._:-]{1,128}") || definitionVersion < 0 || eligibleSubtotalVnd < 0
                || discountVnd < 0 || discountVnd > eligibleSubtotalVnd
                || !expiresAt.equals(orderCreatedAt.plus(Duration.ofMinutes(30)))
                || reservedAt.isBefore(orderCreatedAt) || !reservedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("Invalid voucher claim snapshot");
        }
        switch (state) {
            case RESERVED -> {
                if (transitionedAt != null || paymentReference != null || paidAt != null || releaseReason != null)
                    throw new IllegalArgumentException("Reserved claim has terminal metadata");
            }
            case REDEEMED -> {
                if (transitionedAt == null || paymentReference == null || paymentReference.isBlank() || paidAt == null
                        || releaseReason != null || paidAt.isBefore(reservedAt) || paidAt.isAfter(transitionedAt)
                        || transitionedAt.isBefore(reservedAt) || !transitionedAt.isBefore(expiresAt))
                    throw new IllegalArgumentException("Invalid redemption metadata");
            }
            case RELEASED -> {
                if (transitionedAt == null || releaseReason == null || paymentReference != null || paidAt != null
                        || transitionedAt.isBefore(reservedAt)
                        || (releaseReason == ReleaseReason.EXPIRED && transitionedAt.isBefore(expiresAt)))
                    throw new IllegalArgumentException("Invalid release metadata");
            }
        }
    }

    /** Caller must already have validated current rules/capacity in the Checkout transaction. */
    public static VoucherClaim reserved(String key, Integer voucherId, long definitionVersion,
                                         Integer orderId, Order order, Instant reservedAt) {
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getPaymentStatus() != PaymentStatus.CREATED)
            throw new IllegalArgumentException("Only new pending Orders may establish a reservation");
        return new VoucherClaim(key, voucherId, definitionVersion, order.getUserId(), orderId,
                order.getVoucher().orElseThrow(), order.getEligibleSubtotalVnd(), order.getDiscountVnd(),
                order.getCreatedAt(), order.getExpiresAt(), reservedAt, State.RESERVED, null, null, null, null);
    }

    /** Pure transition only. Caller must verify settlement and lock/reconcile the Order. */
    public VoucherClaim redeem(String reference, Instant verifiedPaidAt, Instant observedAt) {
        Objects.requireNonNull(reference); Objects.requireNonNull(verifiedPaidAt); Objects.requireNonNull(observedAt);
        if (state == State.REDEEMED && reference.equals(paymentReference) && verifiedPaidAt.equals(paidAt)) return this;
        if (state != State.RESERVED) throw new IllegalStateException("Claim is terminal; reconcile conflicting events");
        if (!observedAt.isBefore(expiresAt) || !verifiedPaidAt.isBefore(expiresAt))
            throw new IllegalStateException("Late payment requires reconciliation");
        return new VoucherClaim(claimKey, voucherId, definitionVersion, userId, orderId, terms, eligibleSubtotalVnd,
                discountVnd, orderCreatedAt, expiresAt, reservedAt, State.REDEEMED, observedAt, reference, verifiedPaidAt, null);
    }

    /** Caller must prove final UNPAID cancellation/failure/expiration; never call for a retryable attempt. */
    public VoucherClaim releaseUnpaid(ReleaseReason reason, Instant at) {
        Objects.requireNonNull(reason); Objects.requireNonNull(at);
        if (state == State.RELEASED && reason == releaseReason) return this;
        if (state != State.RESERVED) throw new IllegalStateException("Claim is terminal; reconcile conflicting events");
        return new VoucherClaim(claimKey, voucherId, definitionVersion, userId, orderId, terms, eligibleSubtotalVnd,
                discountVnd, orderCreatedAt, expiresAt, reservedAt, State.RELEASED, at, null, null, reason);
    }
}
