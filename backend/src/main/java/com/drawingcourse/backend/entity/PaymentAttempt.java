package com.drawingcourse.backend.entity;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Immutable attempt snapshot. SUCCEEDED records provider funds, not Order fulfillment authorization. */
public record PaymentAttempt(Integer id, Integer orderId, Integer userId, String provider, String merchantReference,
                             long expectedAmountVnd, String currency, Instant orderCreatedAt, Instant expiresAt,
                             Instant createdAt, Instant updatedAt, PaymentStatus status,
                             String transactionReference, Instant succeededAt, ReviewReason reviewReason) {
    public enum ReviewReason { NONE, AMOUNT_MISMATCH, CURRENCY_MISMATCH, ROUTING_MISMATCH,
        LATE_SUCCESS, ORDER_NOT_PAYABLE, CONFLICTING_SUCCESS, INVALID_EVENT_TIME,
        SETTLEMENT_CONFLICT, VOUCHER_CLAIM_MISMATCH }
    public PaymentAttempt {
        Objects.requireNonNull(orderId); Objects.requireNonNull(userId); Objects.requireNonNull(orderCreatedAt);
        Objects.requireNonNull(expiresAt); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        Objects.requireNonNull(status); Objects.requireNonNull(reviewReason);
        provider = PaymentEvent.reference(provider,64); merchantReference = PaymentEvent.reference(merchantReference,128);
        if (transactionReference != null) transactionReference = PaymentEvent.reference(transactionReference,255);
        if (expectedAmountVnd < 0 || !"VND".equals(currency) || !expiresAt.equals(orderCreatedAt.plus(Duration.ofMinutes(30)))
                || createdAt.isBefore(orderCreatedAt) || !createdAt.isBefore(expiresAt) || updatedAt.isBefore(createdAt)
                || ((status == PaymentStatus.SUCCEEDED) != (succeededAt != null))
                || (succeededAt != null && (transactionReference == null || succeededAt.isBefore(createdAt) || succeededAt.isAfter(updatedAt))))
            throw new IllegalArgumentException("Invalid payment attempt snapshot");
    }

    public static PaymentAttempt create(Integer orderId, Order order, String provider, String merchantReference, Clock clock) {
        if (order.getStatus()!=OrderStatus.PENDING_PAYMENT || order.getPaymentStatus()==PaymentStatus.SUCCEEDED
                || order.getPaymentStatus()==PaymentStatus.UNKNOWN) throw new IllegalStateException("Order is not payable");
        Instant now = Objects.requireNonNull(clock).instant().truncatedTo(ChronoUnit.MICROS);
        return new PaymentAttempt(null,orderId,order.getUserId(),provider,merchantReference,order.getTotalVnd(),"VND",
                order.getCreatedAt(),order.getExpiresAt(),now,now,PaymentStatus.CREATED,null,null,ReviewReason.NONE);
    }

    public PaymentAttempt retry(String newMerchantReference, Order currentOrder, Clock clock) {
        if (status!=PaymentStatus.FAILED || reviewReason!=ReviewReason.NONE || newMerchantReference.equals(merchantReference))
            throw new IllegalStateException("Only a confirmed failed attempt may start a distinct retry");
        if (!userId.equals(currentOrder.getUserId()) || expectedAmountVnd!=currentOrder.getTotalVnd()
                || !orderCreatedAt.equals(currentOrder.getCreatedAt()) || !expiresAt.equals(currentOrder.getExpiresAt()))
            throw new IllegalArgumentException("Retry Order snapshot changed");
        return create(orderId,currentOrder,provider,newMerchantReference,clock);
    }
    public boolean requiresReconciliation() { return reviewReason!=ReviewReason.NONE; }
    public PaymentAttempt withIdentity(Integer persistedId) {
        Objects.requireNonNull(persistedId);
        if (id!=null && !id.equals(persistedId)) throw new IllegalArgumentException("Attempt identity cannot change");
        return new PaymentAttempt(persistedId,orderId,userId,provider,merchantReference,expectedAmountVnd,currency,
                orderCreatedAt,expiresAt,createdAt,updatedAt,status,transactionReference,succeededAt,reviewReason);
    }
}
