package com.drawingcourse.backend.service;

import static com.drawingcourse.backend.service.VoucherCatalogAvailability.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class VoucherCatalogAvailabilityEvaluatorTest {
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-02-01T00:00:00Z");
    private final VoucherCatalogAvailabilityEvaluator evaluator = new VoucherCatalogAvailabilityEvaluator();

    @Test void evaluatesActivationWindowsAndInclusiveExclusiveBoundaries() {
        assertThat(at(input(false, 0, 0, 0, 0), FROM)).isEqualTo(INACTIVE);
        assertThat(at(input(true, 0, 0, 0, 0), FROM.minusNanos(1))).isEqualTo(NOT_STARTED);
        assertThat(at(input(true, 0, 0, 0, 0), FROM)).isEqualTo(POTENTIALLY_AVAILABLE);
        assertThat(at(input(true, 0, 0, 0, 0), UNTIL.minusNanos(1))).isEqualTo(POTENTIALLY_AVAILABLE);
        assertThat(at(input(true, 0, 0, 0, 0), UNTIL)).isEqualTo(EXPIRED);
    }

    @Test void evaluatesTotalThenAuthenticatedUserQuotaWithDeterministicPriority() {
        assertThat(at(input(true, 1, 0, 0, 0), FROM)).isEqualTo(TOTAL_QUOTA_EXHAUSTED);
        assertThat(at(input(true, 0, 0, 1, 0), FROM)).isEqualTo(USER_QUOTA_EXHAUSTED);
        assertThat(at(input(true, 1, 0, 1, 0), FROM)).isEqualTo(TOTAL_QUOTA_EXHAUSTED);
    }

    @Test void potentiallyAvailableNeverClaimsMinimumSpendWasEvaluated() {
        var result = evaluator.evaluate(input(true, 0, 0, 0, 0), FROM);
        assertThat(result.availability()).isEqualTo(POTENTIALLY_AVAILABLE);
        assertThat(result.minimumSpendEvaluated()).isFalse();
    }

    private VoucherCatalogAvailability at(VoucherCatalogAvailabilityEvaluator.Input input, Instant at) {
        return evaluator.evaluate(input, at).availability();
    }

    private VoucherCatalogAvailabilityEvaluator.Input input(boolean active, long totalReserved,
                                                              long totalRedeemed, long userReserved,
                                                              long userRedeemed) {
        return new VoucherCatalogAvailabilityEvaluator.Input(active, FROM, UNTIL,
                OptionalLong.of(1), OptionalLong.of(1), totalReserved, totalRedeemed,
                userReserved, userRedeemed);
    }
}
