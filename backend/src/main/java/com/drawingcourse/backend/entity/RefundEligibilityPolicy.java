package com.drawingcourse.backend.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Pure advisory policy. ELIGIBLE never means a request or Shipment HOLD was persisted. */
public final class RefundEligibilityPolicy {
    public static final int MAX_COURSE_PROGRESS_BASIS_POINTS = 2_000;
    public static final Duration COURSE_REFUND_WINDOW = Duration.ofDays(7);

    public enum Decision { ELIGIBLE, INELIGIBLE, EVIDENCE_UNAVAILABLE, INVALID_EVIDENCE }
    public enum Reason {
        NONE, ORDER_NOT_SETTLED, PRODUCT_BEFORE_HANDOVER, PRODUCT_AFTER_HANDOVER,
        HANDOVER_EVIDENCE_MISSING, HANDOVER_EVIDENCE_AMBIGUOUS,
        COURSE_PROGRESS_WITHIN_LIMIT, COURSE_PROGRESS_OVER_LIMIT, COURSE_ASSIGNMENT_SUBMITTED,
        COURSE_EVIDENCE_MISSING, COURSE_EVIDENCE_INVALID, COURSE_WINDOW_NOT_STARTED, COURSE_WINDOW_EXPIRED
    }
    public enum HandoverState { BEFORE_HANDOVER, HANDED_OVER, MISSING, AMBIGUOUS }
    public enum WindowBoundary { DEADLINE_INCLUSIVE, DEADLINE_EXCLUSIVE }

    public record Result(Decision decision, Reason reason) {
        public Result {
            Objects.requireNonNull(decision); Objects.requireNonNull(reason);
            boolean eligibleReason = reason == Reason.PRODUCT_BEFORE_HANDOVER
                    || reason == Reason.COURSE_PROGRESS_WITHIN_LIMIT;
            if ((decision == Decision.ELIGIBLE) != eligibleReason) {
                throw new IllegalArgumentException("Eligibility decision and reason disagree");
            }
        }
    }

    /** Progress is expected in basis points [0, 10000]; null fields represent incomplete evidence. */
    public record CourseEvidence(Integer progressBasisPoints, Boolean hasSuccessfulAssignment) { }

    public Result evaluateProduct(boolean hasCommittedSettlement, HandoverState handoverState) {
        if (!hasCommittedSettlement) return ineligible(Reason.ORDER_NOT_SETTLED);
        if (handoverState == null || handoverState == HandoverState.MISSING) return unavailable(Reason.HANDOVER_EVIDENCE_MISSING);
        return switch (handoverState) {
            case BEFORE_HANDOVER -> eligible(Reason.PRODUCT_BEFORE_HANDOVER);
            case HANDED_OVER -> ineligible(Reason.PRODUCT_AFTER_HANDOVER);
            case AMBIGUOUS -> unavailable(Reason.HANDOVER_EVIDENCE_AMBIGUOUS);
            case MISSING -> unavailable(Reason.HANDOVER_EVIDENCE_MISSING);
        };
    }

    /** Window origin is explicitly supplied; this method chooses no Order/payment timestamp. */
    public Result evaluateCourse(boolean hasCommittedSettlement, CourseEvidence evidence,
                                 Instant windowStart, Instant evaluatedAt, WindowBoundary boundary) {
        if (!hasCommittedSettlement) return ineligible(Reason.ORDER_NOT_SETTLED);
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(boundary, "boundary");
        if (evidence == null || evidence.progressBasisPoints() == null || evidence.hasSuccessfulAssignment() == null) {
            return unavailable(Reason.COURSE_EVIDENCE_MISSING);
        }
        if (evidence.progressBasisPoints() < 0 || evidence.progressBasisPoints() > 10_000) {
            return invalid(Reason.COURSE_EVIDENCE_INVALID);
        }
        if (evidence.hasSuccessfulAssignment()) return ineligible(Reason.COURSE_ASSIGNMENT_SUBMITTED);
        if (evidence.progressBasisPoints() > MAX_COURSE_PROGRESS_BASIS_POINTS) {
            return ineligible(Reason.COURSE_PROGRESS_OVER_LIMIT);
        }
        Instant deadline = windowStart.plus(COURSE_REFUND_WINDOW);
        if (evaluatedAt.isBefore(windowStart)) return ineligible(Reason.COURSE_WINDOW_NOT_STARTED);
        boolean within = boundary == WindowBoundary.DEADLINE_INCLUSIVE
                ? !evaluatedAt.isAfter(deadline) : evaluatedAt.isBefore(deadline);
        return within ? eligible(Reason.COURSE_PROGRESS_WITHIN_LIMIT) : ineligible(Reason.COURSE_WINDOW_EXPIRED);
    }

    private static Result eligible(Reason reason) { return new Result(Decision.ELIGIBLE, reason); }
    private static Result ineligible(Reason reason) { return new Result(Decision.INELIGIBLE, reason); }
    private static Result unavailable(Reason reason) { return new Result(Decision.EVIDENCE_UNAVAILABLE, reason); }
    private static Result invalid(Reason reason) { return new Result(Decision.INVALID_EVIDENCE, reason); }
}
