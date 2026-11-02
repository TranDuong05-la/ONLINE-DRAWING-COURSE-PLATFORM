package com.drawingcourse.backend.service.settlement;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongUnaryOperator;

/** Pure bounded full-jitter policy for known transient database failures. */
public final class SettlementRetryPolicy {
    private static final long BASE_MILLIS = 5_000;
    private static final long MAX_MILLIS = 15 * 60_000;
    private final LongUnaryOperator randomBelow;

    /** randomBelow must return a value in [0, exclusiveBound). */
    public SettlementRetryPolicy(LongUnaryOperator randomBelow) {
        this.randomBelow = Objects.requireNonNull(randomBelow);
    }

    public Duration delayForCommittedClaim(int attemptCount) {
        if (attemptCount < 1 || attemptCount > 8) throw new IllegalArgumentException("attemptCount must be 1..8");
        long cap = BASE_MILLIS;
        for (int i = 1; i < attemptCount && cap < MAX_MILLIS; i++) cap = Math.min(MAX_MILLIS, cap * 2);
        // Reserve the full configured ceiling for the final automatic claim's failure schedule.
        if (attemptCount == 8) cap = MAX_MILLIS;
        long delay = randomBelow.applyAsLong(cap + 1);
        if (delay < 0 || delay > cap) throw new IllegalStateException("random source returned an out-of-range value");
        return Duration.ofMillis(delay);
    }

    public static SettlementRetryPolicy fullJitter() {
        return new SettlementRetryPolicy(bound -> java.util.concurrent.ThreadLocalRandom.current().nextLong(bound));
    }
}
