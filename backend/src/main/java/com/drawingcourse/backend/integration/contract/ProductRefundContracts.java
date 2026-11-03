package com.drawingcourse.backend.integration.contract;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Pure Member E boundary. It provides no Shipment, packing, or inventory implementation. */
public final class ProductRefundContracts {
    private ProductRefundContracts() { }

    public interface ProductRefundFulfillmentPort {
        HandoverEvidence inspectHandover(HandoverRequest request);

        HoldOwnership acquireHold(AcquireHoldCommand command);

        PackingAdjustmentEvidence applyApprovedAdjustment(AdjustmentCommand command);

        HoldOwnership releaseHold(ReleaseHoldCommand command);
    }

    public enum HandoverState { BEFORE_HANDOVER, HANDED_OVER, UNKNOWN }

    public enum HoldStatus { ACTIVE, RELEASED }

    public record RefundScope(Integer authenticatedUserId, Integer orderId, Integer orderItemId,
                              Long productId) {
        public RefundScope {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            requirePositive(orderId, "orderId");
            requirePositive(orderItemId, "orderItemId");
            requirePositive(productId, "productId");
        }
    }

    public record UnitSelection(int orderItemQuantity, List<Integer> unitPositions) {
        public UnitSelection {
            if (orderItemQuantity <= 0) throw new IllegalArgumentException("orderItemQuantity must be positive");
            unitPositions = canonicalPositions(unitPositions, orderItemQuantity);
        }

        public int requestedQuantity() { return unitPositions.size(); }
    }

    public record HandoverRequest(RefundScope scope, UnitSelection selection, Instant observedAt) {
        public HandoverRequest {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public record HandoverEvidence(RefundScope scope, String shipmentReference, HandoverState state,
                                   Instant handoverCommittedAt, Instant observedAt,
                                   String evidenceReference, long evidenceVersion) {
        public HandoverEvidence {
            Objects.requireNonNull(scope, "scope");
            shipmentReference = requireReference(shipmentReference, "shipmentReference");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(observedAt, "observedAt");
            evidenceReference = requireReference(evidenceReference, "evidenceReference");
            if (evidenceVersion < 0) throw new IllegalArgumentException("evidenceVersion must be nonnegative");
            if ((state == HandoverState.HANDED_OVER) != (handoverCommittedAt != null))
                throw new IllegalArgumentException("Only HANDED_OVER evidence carries a committed timestamp");
            if (handoverCommittedAt != null && handoverCommittedAt.isAfter(observedAt))
                throw new IllegalArgumentException("handover cannot follow observation");
        }

        public void requireBeforeHandover() {
            if (state != HandoverState.BEFORE_HANDOVER)
                throw new IllegalStateException("Product refund requires authoritative BEFORE_HANDOVER evidence");
        }
    }

    public record AcquireHoldCommand(RefundScope scope, UnitSelection selection, String refundOperationId,
                                     HandoverEvidence expectedEvidence, Instant requestedAt) {
        public AcquireHoldCommand {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(selection, "selection");
            refundOperationId = requireReference(refundOperationId, "refundOperationId");
            Objects.requireNonNull(expectedEvidence, "expectedEvidence");
            Objects.requireNonNull(requestedAt, "requestedAt");
            if (!scope.equals(expectedEvidence.scope()))
                throw new IllegalArgumentException("Handover evidence scope mismatch");
            expectedEvidence.requireBeforeHandover();
        }
    }

    /** Ownership is request-specific; releasing one owner must not clear another owner's HOLD. */
    public record HoldOwnership(RefundScope scope, String shipmentReference, String holdReference,
                                String refundOperationId, List<Integer> unitPositions,
                                HoldStatus status, Instant changedAt) {
        public HoldOwnership {
            Objects.requireNonNull(scope, "scope");
            shipmentReference = requireReference(shipmentReference, "shipmentReference");
            holdReference = requireReference(holdReference, "holdReference");
            refundOperationId = requireReference(refundOperationId, "refundOperationId");
            unitPositions = canonicalPositionsWithoutBound(unitPositions);
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(changedAt, "changedAt");
        }
    }

    public record AdjustmentCommand(HoldOwnership activeHold, String adjustmentOperationId,
                                    Instant adjustedAt) {
        public AdjustmentCommand {
            Objects.requireNonNull(activeHold, "activeHold");
            if (activeHold.status() != HoldStatus.ACTIVE)
                throw new IllegalArgumentException("Packing adjustment requires an active HOLD");
            adjustmentOperationId = requireReference(adjustmentOperationId, "adjustmentOperationId");
            Objects.requireNonNull(adjustedAt, "adjustedAt");
        }
    }

    public record PackingAdjustmentEvidence(HoldOwnership hold, String adjustmentReference,
                                            List<Integer> adjustedUnitPositions, Instant adjustedAt) {
        public PackingAdjustmentEvidence {
            Objects.requireNonNull(hold, "hold");
            adjustmentReference = requireReference(adjustmentReference, "adjustmentReference");
            adjustedUnitPositions = canonicalPositionsWithoutBound(adjustedUnitPositions);
            Objects.requireNonNull(adjustedAt, "adjustedAt");
            if (!adjustedUnitPositions.equals(hold.unitPositions()))
                throw new IllegalArgumentException("Packing adjustment must cover the complete held selection");
        }
    }

    public record ReleaseHoldCommand(HoldOwnership activeHold, String releaseOperationId,
                                     Instant releasedAt) {
        public ReleaseHoldCommand {
            Objects.requireNonNull(activeHold, "activeHold");
            if (activeHold.status() != HoldStatus.ACTIVE)
                throw new IllegalArgumentException("Only an active HOLD can be released");
            releaseOperationId = requireReference(releaseOperationId, "releaseOperationId");
            Objects.requireNonNull(releasedAt, "releasedAt");
        }
    }

    private static List<Integer> canonicalPositions(Collection<Integer> source, int quantity) {
        List<Integer> positions = canonicalPositionsWithoutBound(source);
        if (positions.getLast() >= quantity)
            throw new IllegalArgumentException("unit position exceeds OrderItem quantity");
        return positions;
    }

    private static List<Integer> canonicalPositionsWithoutBound(Collection<Integer> source) {
        Objects.requireNonNull(source, "unitPositions");
        if (source.isEmpty()) throw new IllegalArgumentException("unitPositions cannot be empty");
        var sorted = new TreeSet<Integer>();
        for (Integer position : source) {
            if (position == null || position < 0)
                throw new IllegalArgumentException("unit position must be nonnegative");
            if (!sorted.add(position)) throw new IllegalArgumentException("duplicate unit position");
        }
        return List.copyOf(new ArrayList<>(sorted));
    }

    private static String requireReference(String value, String name) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty() || result.length() > 128)
            throw new IllegalArgumentException(name + " must contain 1..128 characters");
        return result;
    }

    private static void requirePositive(Integer value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
