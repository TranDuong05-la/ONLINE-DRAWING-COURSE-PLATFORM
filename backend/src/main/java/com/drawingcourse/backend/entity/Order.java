package com.drawingcourse.backend.entity;

import com.drawingcourse.backend.service.OrderDiscountAllocator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Independent immutable order snapshot. No persistence, checkout, payment or fulfillment side effects. */
public final class Order {
    private final Integer userId;
    private final List<OrderItem> items;
    private final Optional<OrderVoucher> voucher;
    private final long merchandiseSubtotalVnd;
    private final long eligibleSubtotalVnd;
    private final long discountVnd;
    private final long shippingFeeVnd;
    private final long totalVnd;
    private final OrderStatus status;
    private final PaymentStatus paymentStatus;
    private final Instant createdAt;
    private final Instant expiresAt;

    private Order(Integer userId, List<OrderLineSnapshot> input, long shippingFeeVnd,
                  Optional<OrderVoucher> voucher, Clock clock) {
        createdAt = Objects.requireNonNull(clock, "clock").instant().truncatedTo(ChronoUnit.MICROS);
        expiresAt = createdAt.plus(Duration.ofMinutes(30));
        this.userId = Objects.requireNonNull(userId, "userId");
        this.voucher = Objects.requireNonNull(voucher, "voucher");
        var lines = List.copyOf(input);
        if (lines.isEmpty() || shippingFeeVnd < 0) throw new IllegalArgumentException("Invalid order");
        this.shippingFeeVnd = shippingFeeVnd;
        long subtotal = 0;
        long eligible = 0;
        for (var line : lines) {
            subtotal = Math.addExact(subtotal, line.grossAmountVnd());
            if (line.voucherEligible()) eligible = Math.addExact(eligible, line.grossAmountVnd());
        }
        merchandiseSubtotalVnd = subtotal;
        eligibleSubtotalVnd = eligible;
        discountVnd = voucher.isPresent() ? voucher.get().discountFor(eligible) : 0;
        var allocations = new OrderDiscountAllocator().allocate(lines, discountVnd);
        List<OrderItem> snapshots = new ArrayList<>();
        long netSum = 0;
        long discountSum = 0;
        for (int i = 0; i < lines.size(); i++) {
            var item = OrderItem.withDiscount(lines.get(i), allocations.get(i));
            snapshots.add(item);
            netSum = Math.addExact(netSum, item.netAmountVnd());
            discountSum = Math.addExact(discountSum, item.allocatedDiscountVnd());
        }
        if (discountSum != discountVnd || netSum != subtotal - discountVnd) {
            throw new IllegalStateException("Order does not reconcile");
        }
        items = List.copyOf(snapshots);
        totalVnd = Math.addExact(netSum, shippingFeeVnd);
        status = OrderStatus.PENDING_PAYMENT;
        paymentStatus = PaymentStatus.CREATED;
    }

    /** Stored history, not a pricing request. Restore validates arithmetic, never current voucher rules. */
    public record Snapshot(Integer userId, List<OrderItem> items, Optional<OrderVoucher> voucher,
                           String currency, OrderStatus status, PaymentStatus paymentStatus,
                           long merchandiseSubtotalVnd, long eligibleSubtotalVnd, long discountVnd,
                           long shippingFeeVnd, long totalVnd) {
        public Snapshot { items = List.copyOf(items); }
    }

    private Order(Snapshot snapshot, Instant createdAt, Instant expiresAt) {
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Deadline must follow creation");
        }
        userId = Objects.requireNonNull(snapshot.userId(), "userId");
        items = List.copyOf(snapshot.items());
        voucher = Objects.requireNonNull(snapshot.voucher(), "voucher");
        status = Objects.requireNonNull(snapshot.status(), "status");
        paymentStatus = Objects.requireNonNull(snapshot.paymentStatus(), "paymentStatus");
        if (!"VND".equals(snapshot.currency()) || items.isEmpty() || snapshot.shippingFeeVnd() < 0) {
            throw new IllegalArgumentException("Invalid stored order");
        }
        long gross = 0, eligible = 0, discounts = 0, net = 0;
        var references = new java.util.HashSet<String>();
        for (var item : items) {
            var line = item.catalogSnapshot();
            if (!references.add(line.itemType().name() + ":" + line.itemId())) {
                throw new IllegalArgumentException("Duplicate stored item reference");
            }
            gross = Math.addExact(gross, item.grossAmountVnd());
            if (line.voucherEligible()) eligible = Math.addExact(eligible, item.grossAmountVnd());
            discounts = Math.addExact(discounts, item.allocatedDiscountVnd());
            net = Math.addExact(net, item.netAmountVnd());
        }
        if (gross != snapshot.merchandiseSubtotalVnd() || eligible != snapshot.eligibleSubtotalVnd()
                || discounts != snapshot.discountVnd() || discounts > eligible
                || (voucher.isEmpty() && discounts != 0)
                || net != gross - discounts
                || Math.addExact(net, snapshot.shippingFeeVnd()) != snapshot.totalVnd()) {
            throw new IllegalArgumentException("Stored order does not reconcile");
        }
        merchandiseSubtotalVnd = snapshot.merchandiseSubtotalVnd();
        eligibleSubtotalVnd = snapshot.eligibleSubtotalVnd();
        discountVnd = snapshot.discountVnd();
        shippingFeeVnd = snapshot.shippingFeeVnd();
        totalVnd = snapshot.totalVnd();
    }

    public static Order restore(Snapshot snapshot, Instant createdAt, Instant expiresAt) {
        return new Order(Objects.requireNonNull(snapshot, "snapshot"), createdAt, expiresAt);
    }

    /**
     * Returns the confirmed snapshot only from a previously persisted, APPLIED success audit.
     * The caller must load that audit through the trusted reconciliation path after callback
     * signature verification, load the Order under its write lock, and invoke this inside the same
     * REQUIRED transaction that will persist the transition and later settlement effects. The
     * persisted audit value itself is not a cryptographic proof.
     */
    public Order confirmPayment(PaymentAttempt attempt, PaymentEventAudit evidence) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(evidence, "evidence");
        var event = evidence.event();
        boolean validEvidence = evidence.id() != null
                && evidence.attemptId().equals(attempt.id())
                && evidence.orderId().equals(attempt.orderId())
                && evidence.userId().equals(userId)
                && evidence.observedOrderStatus() == OrderStatus.PENDING_PAYMENT
                && evidence.disposition() == PaymentEventAudit.Disposition.APPLIED
                && evidence.reason() == PaymentAttempt.ReviewReason.NONE
                && evidence.afterStatus() == PaymentStatus.SUCCEEDED
                && evidence.fingerprint().equals(event.fingerprint())
                && event.outcome() == PaymentEvent.Outcome.SUCCEEDED
                && event.provider().equals(attempt.provider())
                && event.merchantReference().equals(attempt.merchantReference())
                && Objects.equals(event.transactionReference(), attempt.transactionReference())
                && event.currency().equals(attempt.currency())
                && event.currency().equals(getCurrency())
                && "vnpay-hmac-sha512;paydate=valid".equals(event.verificationReference())
                && event.amountVnd() == attempt.expectedAmountVnd()
                && event.amountVnd() == totalVnd
                && attempt.expectedAmountVnd() == totalVnd
                && attempt.orderCreatedAt().equals(createdAt)
                && Objects.equals(attempt.expiresAt(), expiresAt)
                && !event.occurredAt().isBefore(attempt.createdAt())
                && !event.occurredAt().isAfter(event.receivedAt());
        if (status != OrderStatus.PENDING_PAYMENT || expiresAt == null
                || paymentStatus == PaymentStatus.SUCCEEDED || paymentStatus == PaymentStatus.UNKNOWN
                || paymentStatus == PaymentStatus.CANCELLED || paymentStatus == PaymentStatus.EXPIRED
                || attempt.id() == null || attempt.userId() == null || !attempt.userId().equals(userId)
                || attempt.status() != PaymentStatus.SUCCEEDED
                || attempt.reviewReason() != PaymentAttempt.ReviewReason.NONE
                || attempt.succeededAt() == null || !attempt.succeededAt().equals(event.occurredAt())
                || !event.occurredAt().isBefore(expiresAt) || !event.receivedAt().isBefore(expiresAt)
                || !validEvidence) {
            throw new IllegalStateException("Order cannot be confirmed from this payment evidence");
        }
        return restore(new Snapshot(userId, items, voucher, getCurrency(), OrderStatus.CONFIRMED,
                PaymentStatus.SUCCEEDED, merchandiseSubtotalVnd, eligibleSubtotalVnd, discountVnd,
                shippingFeeVnd, totalVnd), createdAt, expiresAt);
    }

    /** Inputs must be assembled by trusted server integration, never bound from HTTP requests. */
    public static Order create(Integer userId, List<OrderLineSnapshot> lines, long shippingFeeVnd,
                               Optional<OrderVoucher> voucher) {
        return create(userId, lines, shippingFeeVnd, voucher, Clock.systemUTC());
    }

    public static Order create(Integer userId, List<OrderLineSnapshot> lines, long shippingFeeVnd,
                               Optional<OrderVoucher> voucher, Clock clock) {
        return new Order(userId, lines, shippingFeeVnd, voucher, clock);
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }

    /** Candidate check only; a future locked transition must reconcile payment and release claims. */
    public boolean isEligibleForExpiration(Instant now) {
        Objects.requireNonNull(now, "now");
        return status == OrderStatus.PENDING_PAYMENT && expiresAt != null && !now.isBefore(expiresAt)
                && (paymentStatus == PaymentStatus.CREATED || paymentStatus == PaymentStatus.PENDING
                    || paymentStatus == PaymentStatus.FAILED);
    }

    public Integer getUserId() { return userId; }
    public List<OrderItem> getItems() { return items; }
    public Optional<OrderVoucher> getVoucher() { return voucher; }
    public String getCurrency() { return "VND"; }
    public OrderStatus getStatus() { return status; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public long getMerchandiseSubtotalVnd() { return merchandiseSubtotalVnd; }
    public long getEligibleSubtotalVnd() { return eligibleSubtotalVnd; }
    public long getDiscountVnd() { return discountVnd; }
    public long getShippingFeeVnd() { return shippingFeeVnd; }
    public long getTotalVnd() { return totalVnd; }
}
