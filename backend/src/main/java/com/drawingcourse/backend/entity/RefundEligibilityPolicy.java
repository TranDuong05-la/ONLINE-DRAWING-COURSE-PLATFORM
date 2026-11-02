package com.drawingcourse.backend.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    /** Whole-Combo decision; the first failing component is reported deterministically. */
    public record ComboResult(Decision decision, Reason reason, Integer failingComponentIndex) {
        public ComboResult {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reason, "reason");
            if ((decision == Decision.ELIGIBLE) != (failingComponentIndex == null))
                throw new IllegalArgumentException("Combo eligibility and failing component disagree");
        }
    }

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

    /** Every ordered component must pass the same trusted settlement/window evaluation. */
    public ComboResult evaluateCombo(boolean hasCommittedSettlement, List<CourseEvidence> orderedComponents,
                                     Instant windowStart, Instant evaluatedAt, WindowBoundary boundary) {
        Objects.requireNonNull(orderedComponents, "orderedComponents");
        if (orderedComponents.isEmpty())
            return new ComboResult(Decision.INVALID_EVIDENCE, Reason.COURSE_EVIDENCE_INVALID, 0);
        for (int index = 0; index < orderedComponents.size(); index++) {
            Result component = evaluateCourse(hasCommittedSettlement, orderedComponents.get(index),
                    windowStart, evaluatedAt, boundary);
            if (component.decision() != Decision.ELIGIBLE)
                return new ComboResult(component.decision(), component.reason(), index);
        }
        return new ComboResult(Decision.ELIGIBLE, Reason.COURSE_PROGRESS_WITHIN_LIMIT, null);
    }

    private static Result eligible(Reason reason) { return new Result(Decision.ELIGIBLE, reason); }
    private static Result ineligible(Reason reason) { return new Result(Decision.INELIGIBLE, reason); }
    private static Result unavailable(Reason reason) { return new Result(Decision.EVIDENCE_UNAVAILABLE, reason); }
    private static Result invalid(Reason reason) { return new Result(Decision.INVALID_EVIDENCE, reason); }
}
