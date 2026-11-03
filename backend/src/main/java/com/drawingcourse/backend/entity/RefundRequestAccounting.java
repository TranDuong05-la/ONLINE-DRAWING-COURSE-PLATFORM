package com.drawingcourse.backend.entity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Pure idempotency, unit-selection, and amount-reservation rules. It performs no persistence. */
public final class RefundRequestAccounting {
    public static final String FINGERPRINT_VERSION = "refund-request-v1";

    private RefundRequestAccounting() { }

    public enum ScopeType { STANDALONE_COURSE, PRODUCT_UNITS, WHOLE_COMBO }

    public enum FingerprintComparison { IDEMPOTENT_REPLAY, CONFLICT }

    public enum ObligationStatus {
        RESERVED, PROCESSING, UNKNOWN, SUCCEEDED, RELEASED, DEFINITIVELY_FAILED;

        public boolean consumesCapturedAmount() {
            return this == RESERVED || this == PROCESSING || this == UNKNOWN || this == SUCCEEDED;
        }
    }

    public record RequestIdentity(Integer authenticatedUserId, Integer orderId, Integer orderItemId,
                                  ScopeType scopeType, List<Integer> unitPositions, String reasonCode) {
        public RequestIdentity {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            requirePositive(orderId, "orderId");
            requirePositive(orderItemId, "orderItemId");
            Objects.requireNonNull(scopeType, "scopeType");
            unitPositions = canonicalPositions(unitPositions);
            if (scopeType == ScopeType.PRODUCT_UNITS && unitPositions.isEmpty())
                throw new IllegalArgumentException("Product refund requires unit positions");
            if (scopeType != ScopeType.PRODUCT_UNITS && !unitPositions.isEmpty())
                throw new IllegalArgumentException("Whole-item refund cannot contain unit positions");
            reasonCode = boundedText(reasonCode, "reasonCode", 64);
        }

        public String fingerprint() {
            String positions = unitPositions.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            String canonical = field(FINGERPRINT_VERSION)
                    + field(authenticatedUserId.toString())
                    + field(orderId.toString())
                    + field(orderItemId.toString())
                    + field(scopeType.name())
                    + field(positions)
                    + field(reasonCode);
            return sha256(canonical);
        }
    }

    public record AmountObligation(long amountVnd, ObligationStatus status) {
        public AmountObligation {
            if (amountVnd < 0) throw new IllegalArgumentException("amountVnd must be nonnegative");
            Objects.requireNonNull(status, "status");
        }
    }

    public static FingerprintComparison compareFingerprint(String stored, String candidate) {
        String left = requireSha256(stored, "stored fingerprint");
        String right = requireSha256(candidate, "candidate fingerprint");
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII))
                ? FingerprintComparison.IDEMPOTENT_REPLAY : FingerprintComparison.CONFLICT;
    }

    /** Selects the lowest free zero-based unit positions, independent of input collection order. */
    public static List<Integer> selectProductUnits(int orderItemQuantity, int requestedQuantity,
                                                   Collection<Integer> unavailablePositions) {
        if (orderItemQuantity <= 0 || requestedQuantity <= 0 || requestedQuantity > orderItemQuantity)
            throw new IllegalArgumentException("Invalid Product quantity selection");
        var unavailable = new TreeSet<>(canonicalPositions(unavailablePositions));
        if (!unavailable.isEmpty() && unavailable.getLast() >= orderItemQuantity)
            throw new IllegalArgumentException("Unavailable unit exceeds OrderItem quantity");
        var result = new ArrayList<Integer>(requestedQuantity);
        for (int position = 0; position < orderItemQuantity && result.size() < requestedQuantity; position++) {
            if (!unavailable.contains(position)) result.add(position);
        }
        if (result.size() != requestedQuantity)
            throw new IllegalArgumentException("Requested Product quantity is not available");
        return List.copyOf(result);
    }

    public static void requireNoUnitOverlap(Collection<Integer> requested,
                                            Collection<Integer> unavailable) {
        var requestedSet = new TreeSet<>(canonicalPositions(requested));
        for (Integer position : canonicalPositions(unavailable)) {
            if (requestedSet.contains(position))
                throw new IllegalArgumentException("Requested unit is already reserved or refunded");
        }
    }

    public static long reservedOrRefundedAmount(List<AmountObligation> obligations) {
        Objects.requireNonNull(obligations, "obligations");
        long consumed = 0;
        for (AmountObligation obligation : obligations) {
            AmountObligation required = Objects.requireNonNull(obligation, "obligation");
            if (required.status().consumesCapturedAmount())
                consumed = Math.addExact(consumed, required.amountVnd());
        }
        return consumed;
    }

    public static long remainingRefundableAmount(long capturedAmountVnd,
                                                 List<AmountObligation> obligations) {
        if (capturedAmountVnd < 0) throw new IllegalArgumentException("capturedAmountVnd must be nonnegative");
        long consumed = reservedOrRefundedAmount(obligations);
        if (consumed > capturedAmountVnd)
            throw new IllegalArgumentException("Refund obligations exceed captured amount");
        return capturedAmountVnd - consumed;
    }

    public static void requireWithinRemainingCap(long requestedAmountVnd, long remainingItemAmountVnd,
                                                 long remainingCapturedAmountVnd) {
        if (requestedAmountVnd < 0 || remainingItemAmountVnd < 0 || remainingCapturedAmountVnd < 0)
            throw new IllegalArgumentException("Refund amounts must be nonnegative");
        if (requestedAmountVnd > remainingItemAmountVnd
                || requestedAmountVnd > remainingCapturedAmountVnd)
            throw new IllegalArgumentException("Requested refund exceeds the remaining refundable amount");
    }

    public static long wholeCourseAmount(OrderItem item) {
        Objects.requireNonNull(item, "item");
        if (item.catalogSnapshot().itemType() != OrderItemType.COURSE
                || item.catalogSnapshot().quantity() != 1)
            throw new IllegalArgumentException("Whole Course refund requires one COURSE OrderItem");
        return item.netAmountVnd();
    }

    /** Combo inputs are future immutable parent-line snapshots; components and gifts carry no price. */
    public static long wholeComboAmount(int quantity, long grossAmountVnd, long allocatedDiscountVnd,
                                        long netAmountVnd, boolean voucherEligible) {
        if (quantity != 1 || grossAmountVnd < 0 || allocatedDiscountVnd != 0
                || voucherEligible || netAmountVnd != grossAmountVnd)
            throw new IllegalArgumentException("Combo refund requires one voucher-ineligible whole parent line");
        return netAmountVnd;
    }

    private static List<Integer> canonicalPositions(Collection<Integer> source) {
        Objects.requireNonNull(source, "unitPositions");
        var sorted = new TreeSet<Integer>();
        for (Integer position : source) {
            if (position == null || position < 0)
                throw new IllegalArgumentException("unit position must be nonnegative");
            if (!sorted.add(position)) throw new IllegalArgumentException("duplicate unit position");
        }
        return List.copyOf(sorted);
    }

    private static String field(String value) { return value.length() + ":" + value; }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireSha256(String value, String name) {
        String result = Objects.requireNonNull(value, name).strip().toLowerCase(java.util.Locale.ROOT);
        if (!result.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " must be SHA-256 hex");
        return result;
    }

    private static String boundedText(String value, String name, int maximumLength) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty() || result.length() > maximumLength)
            throw new IllegalArgumentException(name + " must contain 1.." + maximumLength + " characters");
        return result;
    }

    private static void requirePositive(Integer value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
