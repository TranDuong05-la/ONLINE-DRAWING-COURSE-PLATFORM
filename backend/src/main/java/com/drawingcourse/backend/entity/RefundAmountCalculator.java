package com.drawingcourse.backend.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure quote over immutable OrderItem snapshots; it does not reserve or authorize a refund. */
public final class RefundAmountCalculator {
    public record UnitRange(int firstUnit, int unitCount) {
        public UnitRange {
            if (firstUnit < 0 || unitCount < 1) {
                throw new IllegalArgumentException("A unit range must have a nonnegative start and positive quantity");
            }
        }
        long endExclusive() { return Math.addExact((long) firstUnit, unitCount); }
    }

    public record Calculation(List<UnitRange> requestedRanges, long amountVnd, long originalLineNetVnd) {
        public Calculation {
            requestedRanges = List.copyOf(requestedRanges);
            if (amountVnd < 0 || originalLineNetVnd < 0 || amountVnd > originalLineNetVnd) {
                throw new IllegalArgumentException("Invalid calculated refund amount");
            }
        }
    }

    /**
     * Calculates requested ranges only. Existing completed and active obligations are
     * exclusions, not amounts to subtract again. The caller must persist reservations
     * atomically; this pure check cannot prevent concurrent callers selecting one unit.
     */
    public Calculation calculateProduct(OrderItem item, List<UnitRange> requested, List<UnitRange> unavailable,
                                         long remainingAmountCapVnd) {
        Objects.requireNonNull(item, "item");
        if (item.catalogSnapshot().itemType() != OrderItemType.PRODUCT) {
            throw new IllegalArgumentException("Only PRODUCT OrderItems support quantity refund calculation");
        }
        if (remainingAmountCapVnd < 0) throw new IllegalArgumentException("Remaining amount cap cannot be negative");
        int quantity = item.catalogSnapshot().quantity();
        var requestedRanges = validateAndSort(requested, quantity, "requested", false);
        var unavailableRanges = validateAndSort(unavailable, quantity, "unavailable", true);
        rejectInternalOverlaps(requestedRanges, "Requested ranges overlap");
        rejectInternalOverlaps(unavailableRanges, "Existing refund/reservation ranges overlap");
        rejectCrossOverlaps(requestedRanges, unavailableRanges);

        long amount = 0;
        for (var range : requestedRanges) {
            amount = Math.addExact(amount, item.netAmountForUnits(range.firstUnit(), range.unitCount()));
        }
        if (amount > item.netAmountVnd() || amount > remainingAmountCapVnd) {
            throw new IllegalArgumentException("Requested refund exceeds the remaining net paid amount");
        }
        return new Calculation(requestedRanges, amount, item.netAmountVnd());
    }

    private static List<UnitRange> validateAndSort(List<UnitRange> ranges, int itemQuantity, String label,
                                                   boolean allowEmpty) {
        Objects.requireNonNull(ranges, label + " ranges");
        if (!allowEmpty && ranges.isEmpty()) throw new IllegalArgumentException(label + " ranges cannot be empty");
        var result = new ArrayList<>(ranges);
        for (var range : result) {
            Objects.requireNonNull(range, label + " range");
            if (range.endExclusive() > itemQuantity) throw new IllegalArgumentException(label + " range exceeds quantity");
        }
        result.sort(Comparator.comparingInt(UnitRange::firstUnit));
        return List.copyOf(result);
    }

    private static void rejectInternalOverlaps(List<UnitRange> ranges, String message) {
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i - 1).endExclusive() > ranges.get(i).firstUnit()) throw new IllegalArgumentException(message);
        }
    }

    private static void rejectCrossOverlaps(List<UnitRange> requested, List<UnitRange> unavailable) {
        int i = 0, j = 0;
        while (i < requested.size() && j < unavailable.size()) {
            var a = requested.get(i); var b = unavailable.get(j);
            if (a.firstUnit() < b.endExclusive() && b.firstUnit() < a.endExclusive()) {
                throw new IllegalArgumentException("Requested unit is already refunded or reserved");
            }
            if (a.endExclusive() <= b.firstUnit()) i++; else j++;
        }
    }
}
