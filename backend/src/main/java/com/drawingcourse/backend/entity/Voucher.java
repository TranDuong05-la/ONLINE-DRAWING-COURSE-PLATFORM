package com.drawingcourse.backend.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/** Independent immutable voucher definition. BR-VOUCHER-01 has no category configuration. */
public record Voucher(String code, OrderVoucher.Kind kind, long value, OptionalLong percentageCapVnd,
                      boolean active, Instant validFrom, Instant validUntil, long minimumSubtotalVnd,
                      OptionalLong totalUsageLimit, OptionalLong perUserUsageLimit) {
    public Voucher {
        code = Objects.requireNonNull(code, "code").strip();
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(percentageCapVnd, "percentageCapVnd");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(totalUsageLimit, "totalUsageLimit");
        Objects.requireNonNull(perUserUsageLimit, "perUserUsageLimit");
        if (code.isEmpty() || code.length() > 255 || value < 0 || minimumSubtotalVnd < 0
                || !validUntil.isAfter(validFrom)
                || (percentageCapVnd.isPresent() && percentageCapVnd.getAsLong() < 0)
                || (kind == OrderVoucher.Kind.FIXED_VND && percentageCapVnd.isPresent())
                || (kind == OrderVoucher.Kind.PERCENT_BASIS_POINTS && value > 10000)
                || (totalUsageLimit.isPresent() && totalUsageLimit.getAsLong() < 0)
                || (perUserUsageLimit.isPresent() && perUserUsageLimit.getAsLong() < 0)) {
            throw new IllegalArgumentException("Invalid voucher definition");
        }
    }

    public Voucher withActive(boolean enabled) {
        return new Voucher(code, kind, value, percentageCapVnd, enabled, validFrom, validUntil,
                minimumSubtotalVnd, totalUsageLimit, perUserUsageLimit);
    }

    /** Frozen rule metadata only. Use the validation service before applying it to a new order. */
    public OrderVoucher toOrderSnapshot() {
        long cap = kind == OrderVoucher.Kind.FIXED_VND ? value : percentageCapVnd.orElse(Long.MAX_VALUE);
        return new OrderVoucher(code, kind, value, cap);
    }
}
