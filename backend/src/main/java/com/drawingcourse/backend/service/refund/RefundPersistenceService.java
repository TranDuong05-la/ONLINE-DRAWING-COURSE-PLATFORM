package com.drawingcourse.backend.service.refund;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import static com.drawingcourse.backend.entity.RefundPersistenceTypes.*;

/** Persistence-only Refund foundation. It invokes no provider and no Member C/E adapter. */
@Service
public class RefundPersistenceService {
    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final PaymentSettlementRepository settlements;
    private final OrderItemRepository items;
    private final RefundRequestRepository requests;
    private final RefundUnitAllocationRepository units;
    private final RefundProviderAttemptRepository providerAttempts;

    public RefundPersistenceService(OrderRepository orders, PaymentAttemptRepository attempts,
                                    PaymentSettlementRepository settlements, OrderItemRepository items,
                                    RefundRequestRepository requests, RefundUnitAllocationRepository units,
                                    RefundProviderAttemptRepository providerAttempts) {
        this.orders = orders; this.attempts = attempts; this.settlements = settlements; this.items = items;
        this.requests = requests; this.units = units; this.providerAttempts = providerAttempts;
    }

    /** Locks the established prefix, reserves captured money, and persists deterministic unit positions atomically. */
    @Transactional
    public CreationResult create(CreateCommand command) {
        Objects.requireNonNull(command, "command");
        var order = orders.findForUpdate(command.orderId, command.userId)
                .orElseThrow(() -> new IllegalArgumentException("Order is not owned by the authenticated user"));
        var lockedAttempts = attempts.findAllForOrderUpdate(command.orderId, command.userId);
        var settlement = settlements.findForUpdateByOrderId(command.orderId)
                .orElseThrow(() -> new IllegalStateException("Order has no committed settlement"));
        var item = items.findForUpdate(command.orderItemId, command.orderId, command.userId)
                .orElseThrow(() -> new IllegalArgumentException("OrderItem is not owned by the authenticated user"));

        var existing = requests.findByUserIdAndIdempotencyKey(command.userId, command.idempotencyKey);
        if (existing.isPresent()) {
            if (existing.get().getRequestFingerprint().equals(command.requestFingerprint))
                return new CreationResult(existing.get(), units.findAllByRefundRequestIdOrderByUnitPosition(existing.get().getId()), true);
            throw new RequestIdempotencyConflict(existing.get().getId(), existing.get().getRequestFingerprint(),
                    command.requestFingerprint, command.requestedAt);
        }

        var paidAttempt = lockedAttempts.stream().filter(a -> a.getId().equals(settlement.getPaymentAttemptId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Settlement PaymentAttempt route is inconsistent"));
        if (paidAttempt.getStatus() != PaymentStatus.SUCCEEDED)
            throw new IllegalStateException("Settlement PaymentAttempt is not successful");
        if (!paidAttempt.getCurrency().equals(order.getCurrency()))
            throw new IllegalStateException("Settlement currency does not match the Order");
        if (paidAttempt.getExpectedAmountVnd() != order.getTotalVnd())
            throw new IllegalStateException("Captured amount does not match the settled Order total");

        var active = units.findActiveForItemUpdate(item.getId());
        List<Integer> positions;
        ItemType itemType;
        ScopeType scope;
        if (item.getItemType() == OrderItemType.PRODUCT) {
            itemType = ItemType.PRODUCT; scope = ScopeType.PRODUCT_UNITS;
            positions = RefundRequestAccounting.selectProductUnits(item.getQuantity(), command.requestedQuantity,
                    active.stream().map(RefundUnitAllocationJpaEntity::getUnitPosition).toList());
        } else {
            if (command.requestedQuantity != 1) throw new IllegalArgumentException("Course refund must reserve the whole item");
            itemType = ItemType.COURSE; scope = ScopeType.STANDALONE_COURSE; positions = List.of(0);
        }

        var amounts = positions.stream().map(position -> unitAmount(item.getNetAmountVnd(), item.getQuantity(), position)).toList();
        long requestedAmount = amounts.stream().reduce(0L, Math::addExact);
        if (requestedAmount <= 0) throw new IllegalArgumentException("Financial Refund amount must be positive");
        long itemCommitted = active.stream().map(RefundUnitAllocationJpaEntity::getAllocatedAmountVnd).reduce(0L, Math::addExact);
        if (Math.addExact(itemCommitted, requestedAmount) > item.getNetAmountVnd())
            throw new IllegalArgumentException("Refund obligations exceed OrderItem net amount");

        var obligations = requests.findObligationsForUpdate(settlement.getId(),
                EnumSet.of(AmountReservationStatus.RESERVED, AmountReservationStatus.CONSUMED));
        long capturedCommitted = obligations.stream().map(RefundRequestJpaEntity::getRequestedAmountVnd).reduce(0L, Math::addExact);
        if (Math.addExact(capturedCommitted, requestedAmount) > paidAttempt.getExpectedAmountVnd())
            throw new IllegalArgumentException("Refund obligations exceed captured amount");

        var snapshot = new RefundRequestJpaEntity.CreateSnapshot(command.userId, command.orderId, item.getId(),
                settlement.getId(), command.idempotencyKey, command.requestFingerprint, scope, command.reasonCode,
                command.customerNote, itemType, item.getQuantity(), item.getGrossAmountVnd(), item.getAllocatedDiscountVnd(),
                item.getNetAmountVnd(), item.isVoucherEligible(), command.requestedQuantity, requestedAmount,
                paidAttempt.getCurrency(), paidAttempt.getExpectedAmountVnd(), settlement.getProviderPaidAt(),
                settlement.getSettledAt(), command.physicalEffectRequired, command.requestedAt);
        var request = requests.saveAndFlush(RefundRequestJpaEntity.create(snapshot));
        var savedUnits = new ArrayList<RefundUnitAllocationJpaEntity>();
        for (int index = 0; index < positions.size(); index++)
            savedUnits.add(units.saveAndFlush(RefundUnitAllocationJpaEntity.reserve(request.getId(), item.getId(),
                    positions.get(index), amounts.get(index), command.requestedAt)));
        return new CreationResult(request, List.copyOf(savedUnits), false);
    }

    @Transactional
    public RefundProviderAttemptJpaEntity prepareProviderAttempt(Long requestId, Integer userId, String provider,
                                                                 String operationKey, Instant at) {
        var request = requests.findForUpdate(requestId, userId).orElseThrow();
        if (request.getFinancialStatus() != FinancialStatus.READY)
            throw new IllegalStateException("Refund is not ready for provider execution");
        if (providerAttempts.findActiveSlotForUpdate(requestId).isPresent())
            throw new IllegalStateException("Refund already owns an active provider execution slot");
        int number = Math.toIntExact(providerAttempts.countByRefundRequestId(requestId) + 1);
        return providerAttempts.saveAndFlush(RefundProviderAttemptJpaEntity.pending(requestId, number, provider,
                operationKey, request.getRequestedAmountVnd(), request.getCurrency(), request.getRequestFingerprint(), at));
    }

    @Transactional
    public void startProviderAttempt(Long requestId, Integer userId, Long attemptId, Instant at) {
        var request = requests.findForUpdate(requestId, userId).orElseThrow();
        var attempt = providerAttempts.findForUpdate(attemptId, requestId).orElseThrow();
        attempt.start(at); request.startFinancial(at);
    }

    @Transactional
    public void recordUnknown(Long requestId, Integer userId, Long attemptId, String responseFingerprint,
                              String evidenceReference, Instant at) {
        var request = requests.findForUpdate(requestId, userId).orElseThrow();
        var attempt = providerAttempts.findForUpdate(attemptId, requestId).orElseThrow();
        attempt.markUnknown(responseFingerprint, evidenceReference, at); request.markFinancialUnknown(at);
    }

    private static long unitAmount(long net, int quantity, int position) {
        long base = net / quantity; long remainder = net % quantity;
        return Math.addExact(base, position < remainder ? 1 : 0);
    }

    public record CreateCommand(Integer userId, Integer orderId, Integer orderItemId, String idempotencyKey,
                                String requestFingerprint, int requestedQuantity, String reasonCode,
                                String customerNote, boolean physicalEffectRequired, Instant requestedAt) { }
    public record CreationResult(RefundRequestJpaEntity request, List<RefundUnitAllocationJpaEntity> allocations,
                                 boolean idempotentReplay) { }
    public static final class RequestIdempotencyConflict extends RuntimeException {
        private final Long requestId; private final String originalFingerprint; private final String conflictingFingerprint; private final Instant observedAt;
        RequestIdempotencyConflict(Long requestId, String original, String conflicting, Instant observedAt) {
            super("Refund idempotency key has a different canonical fingerprint"); this.requestId=requestId;
            this.originalFingerprint=original; this.conflictingFingerprint=conflicting; this.observedAt=observedAt;
        }
        public Long requestId() { return requestId; } public String originalFingerprint() { return originalFingerprint; }
        public String conflictingFingerprint() { return conflictingFingerprint; } public Instant observedAt() { return observedAt; }
    }
}
