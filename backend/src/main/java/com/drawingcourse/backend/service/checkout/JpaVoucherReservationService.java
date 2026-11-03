package com.drawingcourse.backend.service.checkout;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.VoucherClaimRepository;
import com.drawingcourse.backend.repository.VoucherRepository;
import com.drawingcourse.backend.repository.VoucherUserUsageRepository;
import com.drawingcourse.backend.service.StoredOrder;
import com.drawingcourse.backend.service.VoucherPersistenceMapper;
import com.drawingcourse.backend.service.settlement.VoucherConsumptionPort;
import com.drawingcourse.backend.service.expiration.VoucherExpirationReleasePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Local JPA voucher reservation participant. Every operation is MANDATORY: it must join the Checkout,
 * cancellation/expiration, or verified-settlement transaction that owns the corresponding Order transition.
 */
@Service
public final class JpaVoucherReservationService implements VoucherReservationPort, VoucherConsumptionPort,
        VoucherExpirationReleasePort {
    private final VoucherRepository vouchers;
    private final VoucherUserUsageRepository userUsage;
    private final VoucherClaimRepository claims;
    private final OrderRepository orders;
    private final VoucherPersistenceMapper mapper = new VoucherPersistenceMapper();
    private final TransactionTemplate transaction;

    public JpaVoucherReservationService(VoucherRepository vouchers, VoucherUserUsageRepository userUsage,
            VoucherClaimRepository claims, OrderRepository orders, PlatformTransactionManager transactionManager) {
        this.vouchers = Objects.requireNonNull(vouchers);
        this.userUsage = Objects.requireNonNull(userUsage);
        this.claims = Objects.requireNonNull(claims);
        this.orders = Objects.requireNonNull(orders);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override
    public Optional<Quote> quoteAndLock(Integer userId, String code, long eligibleSubtotalVnd, Instant observedAt) {
        requirePositive(userId, "userId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(observedAt, "observedAt");
        if (code.isBlank() || eligibleSubtotalVnd < 0) return Optional.empty();
        return inTransaction(() -> {
            // Global lock order begins with the voucher definition. This serializes capacity checks,
            // including the missing per-user usage-row case, before any counter is observed or created.
            var definition = vouchers.findByCodeForUpdate(code.strip());
            if (definition.isEmpty()) return Optional.empty();
            VoucherJpaEntity row = definition.orElseThrow();
            Voucher voucher = mapper.toDomain(row);
            if (!voucher.active() || observedAt.isBefore(voucher.validFrom())
                    || !observedAt.isBefore(voucher.validUntil())
                    || eligibleSubtotalVnd < voucher.minimumSubtotalVnd()) return Optional.empty();

            VoucherUserUsageJpaEntity usage = userUsage.findForUpdate(row.getId(), userId).orElse(null);
            long totalUsed = Math.addExact(row.getReservedCount(), row.getRedeemedCount());
            long userUsed = usage == null ? 0L : Math.addExact(usage.getReservedCount(), usage.getRedeemedCount());
            if ((voucher.totalUsageLimit().isPresent() && totalUsed >= voucher.totalUsageLimit().getAsLong())
                    || (voucher.perUserUsageLimit().isPresent() && userUsed >= voucher.perUserUsageLimit().getAsLong())) {
                return Optional.empty();
            }

            OrderVoucher terms = voucher.toOrderSnapshot();
            try {
                terms.discountFor(eligibleSubtotalVnd);
            } catch (IllegalArgumentException invalidDiscount) {
                return Optional.empty();
            }
            return Optional.of(new Quote(row.getId(), row.getDefinitionVersion(), terms));
        });
    }

    @Override
    public void reserveForOrder(Integer userId, StoredOrder storedOrder, Order order, Quote quote, Instant reservedAt) {
        requirePositive(userId, "userId");
        Objects.requireNonNull(storedOrder, "storedOrder");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(reservedAt, "reservedAt");
        inTransaction(() -> {
            if (storedOrder.id() == null || !userId.equals(order.getUserId())
                    || !userId.equals(storedOrder.snapshot().getUserId())
                    || !storedOrder.paymentExpiresAt().equals(order.getExpiresAt())
                    || !storedOrder.snapshot().getVoucher().equals(order.getVoucher())
                    || storedOrder.snapshot().getDiscountVnd() != order.getDiscountVnd()
                    || storedOrder.snapshot().getEligibleSubtotalVnd() != order.getEligibleSubtotalVnd()
                    || order.getVoucher().isEmpty() || !order.getVoucher().orElseThrow().equals(quote.terms())) {
                throw new IllegalArgumentException("Voucher quote, owner, or persisted Order snapshot mismatch");
            }

            // Same Order retries are idempotent. A different voucher cannot replace its immutable claim.
            var route = claims.findLockRouteByOrderIdAndUserId(storedOrder.id(), userId);
            if (route.isPresent() && !quote.voucherId().equals(route.orElseThrow().getVoucherId())) {
                throw new IllegalStateException("Order already has a claim for another voucher");
            }

            VoucherJpaEntity voucher = vouchers.findByIdForUpdate(quote.voucherId())
                    .orElseThrow(() -> new IllegalStateException("Quoted voucher definition disappeared"));

            var existing = claims.findForUpdate(storedOrder.id(), userId);
            if (existing.isPresent()) {
                VoucherClaimJpaEntity claim = existing.orElseThrow();
                if (claim.getState() == VoucherClaim.State.RESERVED
                        && claim.getVoucherId().equals(voucher.getId())
                        && claim.getDefinitionVersion() == quote.definitionVersion()
                        && claim.getCodeSnapshot().equals(quote.terms().code())
                        && claim.getKindSnapshot() == quote.terms().kind()
                        && claim.getValueSnapshot() == quote.terms().value()
                        && claim.getCapSnapshotVnd() == quote.terms().maximumDiscountVnd()
                        && claim.getEligibleSubtotalVnd() == order.getEligibleSubtotalVnd()
                        && claim.getDiscountVnd() == order.getDiscountVnd()
                        && claim.getOrderCreatedAt().equals(order.getCreatedAt())
                        && claim.getExpiresAt().equals(order.getExpiresAt())) return null;
                throw new IllegalStateException("Order already has a different or terminal voucher claim");
            }

            if (voucher.getDefinitionVersion() != quote.definitionVersion())
                throw new IllegalStateException("Voucher definition changed after it was quoted");
            VoucherUserUsageJpaEntity usage = getOrCreateUsage(voucher.getId(), userId);

            Instant effectiveReservedAt = reservedAt.isBefore(order.getCreatedAt())
                    ? order.getCreatedAt() : reservedAt;
            VoucherClaim snapshot = VoucherClaim.reserved(claimKey(storedOrder.id()), voucher.getId(),
                    quote.definitionVersion(), storedOrder.id(), order, effectiveReservedAt);
            voucher.reserveOne();
            usage.reserveOne();
            vouchers.saveAndFlush(voucher);
            userUsage.saveAndFlush(usage);
            claims.saveAndFlush(mapper.toNewClaimSnapshot(snapshot));
            return null;
        });
    }

    @Override
    public void releaseForOrder(Integer userId, Integer orderId, VoucherClaim.ReleaseReason reason, Instant releasedAt) {
        requirePositive(userId, "userId");
        requirePositive(orderId, "orderId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(releasedAt, "releasedAt");
        inTransaction(() -> {
            OrderJpaEntity order = orders.findForUpdate(orderId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found for owner"));
            requireTerminalUnpaidOrder(order, reason, releasedAt);
            VoucherClaimJpaEntity claim = lockClaimAndCounters(order, userId);
            if (claim == null) return null; // An Order without a voucher has nothing to release.
            VoucherClaim.State oldState = claim.getState();
            claim.release(reason, releasedAt);
            if (oldState == VoucherClaim.State.RESERVED) {
                countersFor(claim).releaseOne();
                userUsage.findForUpdate(claim.getVoucherId(), userId)
                        .orElseThrow(() -> new IllegalStateException("Voucher user usage row missing for reserved claim"))
                        .releaseOne();
            }
            claims.saveAndFlush(claim);
            saveCountersFor(claim);
            return null;
        });
    }

    @Override
    public void releaseVoucherForExpiredOrder(Integer userId, Integer orderId, Instant releasedAt) {
        releaseForOrder(userId, orderId, VoucherClaim.ReleaseReason.EXPIRED, releasedAt);
    }

    @Override
    public void consumeForOrder(Integer userId, Integer orderId, String paymentReference,
                                Instant verifiedPaidAt, Instant observedAt) {
        requirePositive(userId, "userId");
        requirePositive(orderId, "orderId");
        Objects.requireNonNull(paymentReference, "paymentReference");
        Objects.requireNonNull(verifiedPaidAt, "verifiedPaidAt");
        Objects.requireNonNull(observedAt, "observedAt");
        inTransaction(() -> {
            OrderJpaEntity order = orders.findForUpdate(orderId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found for owner"));
            if ((order.getStatus() != OrderStatus.CONFIRMED && order.getStatus() != OrderStatus.COMPLETED)
                    || order.getPaymentStatus() != PaymentStatus.SUCCEEDED) {
                throw new IllegalStateException("Voucher can be consumed only for a settled Order");
            }
            VoucherClaimJpaEntity claim = lockClaimAndCounters(order, userId);
            if (claim == null) return null;
            VoucherClaim.State oldState = claim.getState();
            claim.redeem(paymentReference, verifiedPaidAt, observedAt); // Enforces the exclusive deadline.
            if (oldState == VoucherClaim.State.RESERVED) {
                countersFor(claim).redeemOne();
                userUsage.findForUpdate(claim.getVoucherId(), userId)
                        .orElseThrow(() -> new IllegalStateException("Voucher user usage row missing for reserved claim"))
                        .redeemOne();
            }
            claims.saveAndFlush(claim);
            saveCountersFor(claim);
            return null;
        });
    }

    private VoucherClaimJpaEntity lockClaimAndCounters(OrderJpaEntity order, Integer userId) {
        if (order.getVoucherCode() == null) return null;
        var route = claims.findLockRouteByOrderIdAndUserId(order.getId(), userId)
                .orElseThrow(() -> new IllegalStateException("Voucher Order has no persisted claim"));
        VoucherJpaEntity voucher = vouchers.findByIdForUpdate(route.getVoucherId())
                .orElseThrow(() -> new IllegalStateException("Voucher definition missing for claim"));
        userUsage.findForUpdate(voucher.getId(), userId)
                .orElseThrow(() -> new IllegalStateException("Voucher user usage row missing for claim"));
        return claims.findForUpdate(order.getId(), userId)
                .orElseThrow(() -> new IllegalStateException("Voucher claim disappeared while locked"));
    }

    private VoucherJpaEntity countersFor(VoucherClaimJpaEntity claim) {
        return vouchers.findByIdForUpdate(claim.getVoucherId())
                .orElseThrow(() -> new IllegalStateException("Voucher definition missing for claim"));
    }

    private void saveCountersFor(VoucherClaimJpaEntity claim) {
        VoucherJpaEntity voucher = countersFor(claim);
        VoucherUserUsageJpaEntity usage = userUsage.findForUpdate(claim.getVoucherId(), claim.getUserId())
                .orElseThrow(() -> new IllegalStateException("Voucher user usage row missing for claim"));
        vouchers.saveAndFlush(voucher);
        userUsage.saveAndFlush(usage);
    }

    private VoucherUserUsageJpaEntity getOrCreateUsage(Integer voucherId, Integer userId) {
        return userUsage.findForUpdate(voucherId, userId)
                .orElseGet(() -> userUsage.saveAndFlush(new VoucherUserUsageJpaEntity(voucherId, userId)));
    }

    private static void requireTerminalUnpaidOrder(OrderJpaEntity order, VoucherClaim.ReleaseReason reason, Instant at) {
        boolean unpaid = order.getPaymentStatus() != PaymentStatus.PENDING
                && order.getPaymentStatus() != PaymentStatus.UNKNOWN
                && order.getPaymentStatus() != PaymentStatus.SUCCEEDED;
        boolean terminal = switch (reason) {
            case CANCELLED -> order.getStatus() == OrderStatus.CANCELLED;
            case EXPIRED -> order.getStatus() == OrderStatus.EXPIRED
                    && order.getPaymentStatus() == PaymentStatus.EXPIRED
                    && order.getPaymentExpiresAt() != null && !at.isBefore(order.getPaymentExpiresAt());
            case FINAL_FAILURE -> order.getStatus() == OrderStatus.CANCELLED
                    && order.getPaymentStatus() == PaymentStatus.FAILED;
        };
        if (!unpaid || !terminal) throw new IllegalStateException("Voucher release requires a terminal unpaid Order state");
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Voucher reservation must join its caller's transaction");
        return transaction.execute(status -> work.get());
    }

    private static String claimKey(Integer orderId) { return "checkout-v1:voucher:" + orderId; }
    private static void requirePositive(Integer value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
