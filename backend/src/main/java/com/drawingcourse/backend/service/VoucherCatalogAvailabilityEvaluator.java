package com.drawingcourse.backend.service;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/** Pure advisory evaluator. It never evaluates minimum spend and never reserves quota. */
public final class VoucherCatalogAvailabilityEvaluator {
    public Evaluation evaluate(Input input, Instant observedAt) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(observedAt, "observedAt");
        input.validate();

        VoucherCatalogAvailability availability;
        if (!input.active()) {
            availability = VoucherCatalogAvailability.INACTIVE;
        } else if (observedAt.isBefore(input.validFrom())) {
            availability = VoucherCatalogAvailability.NOT_STARTED;
        } else if (!observedAt.isBefore(input.validUntil())) {
            availability = VoucherCatalogAvailability.EXPIRED;
        } else if (exhausted(input.totalUsageLimit(), input.reservedCount(), input.redeemedCount())) {
            availability = VoucherCatalogAvailability.TOTAL_QUOTA_EXHAUSTED;
        } else if (exhausted(input.perUserUsageLimit(), input.userReservedCount(), input.userRedeemedCount())) {
            availability = VoucherCatalogAvailability.USER_QUOTA_EXHAUSTED;
        } else {
            availability = VoucherCatalogAvailability.POTENTIALLY_AVAILABLE;
        }
        return new Evaluation(availability, false);
    }

    private static boolean exhausted(OptionalLong limit, long reserved, long redeemed) {
        return limit.isPresent() && Math.addExact(reserved, redeemed) >= limit.getAsLong();
    }

    public record Input(boolean active, Instant validFrom, Instant validUntil,
                        OptionalLong totalUsageLimit, OptionalLong perUserUsageLimit,
                        long reservedCount, long redeemedCount,
                        long userReservedCount, long userRedeemedCount) {
        public Input {
            Objects.requireNonNull(validFrom, "validFrom");
            Objects.requireNonNull(validUntil, "validUntil");
            Objects.requireNonNull(totalUsageLimit, "totalUsageLimit");
            Objects.requireNonNull(perUserUsageLimit, "perUserUsageLimit");
        }

        private void validate() {
            if (!validUntil.isAfter(validFrom)
                    || reservedCount < 0 || redeemedCount < 0
                    || userReservedCount < 0 || userRedeemedCount < 0
                    || (totalUsageLimit.isPresent() && totalUsageLimit.getAsLong() < 0)
                    || (perUserUsageLimit.isPresent() && perUserUsageLimit.getAsLong() < 0)) {
                throw new IllegalArgumentException("Invalid voucher availability input");
            }
        }
    }

    public record Evaluation(VoucherCatalogAvailability availability, boolean minimumSpendEvaluated) {
        public Evaluation {
            Objects.requireNonNull(availability, "availability");
        }
    }
}
