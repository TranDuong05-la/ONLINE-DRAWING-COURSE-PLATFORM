package com.drawingcourse.backend.entity;

import java.math.BigInteger;
import java.util.Objects;

/** Immutable already-validated voucher rule snapshot; eligibility/usage checks are external. */
public record OrderVoucher(String code, Kind kind, long value, long maximumDiscountVnd) {
    public enum Kind { FIXED_VND, PERCENT_BASIS_POINTS }

    public OrderVoucher {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(kind, "kind");
        if (code.isBlank() || value < 0 || maximumDiscountVnd < 0
                || (kind == Kind.PERCENT_BASIS_POINTS && value > 10000)
                || (kind == Kind.FIXED_VND && value > maximumDiscountVnd)) {
            throw new IllegalArgumentException("Invalid voucher rule");
        }
    }

    /** Percentages round down to whole VND, then apply the cap. Oversized fixed amounts reject. */
    public long discountFor(long eligibleSubtotalVnd) {
        if (eligibleSubtotalVnd < 0) throw new IllegalArgumentException("Negative eligible subtotal");
        if (kind == Kind.FIXED_VND) {
            if (value > eligibleSubtotalVnd) throw new IllegalArgumentException("Discount exceeds eligible subtotal");
            return value;
        }
        long amount = BigInteger.valueOf(eligibleSubtotalVnd).multiply(BigInteger.valueOf(value))
                .divide(BigInteger.valueOf(10000)).longValueExact();
        return Math.min(amount, maximumDiscountVnd);
    }
}
