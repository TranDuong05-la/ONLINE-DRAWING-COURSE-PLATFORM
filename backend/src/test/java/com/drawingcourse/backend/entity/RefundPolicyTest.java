package com.drawingcourse.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RefundPolicyTest {
    private final RefundAmountCalculator amounts = new RefundAmountCalculator();
    private final RefundEligibilityPolicy eligibility = new RefundEligibilityPolicy();

    @Test
    void calculatesWholeAndPartialProductQuantitiesFromSnapshot() {
        for (int quantity : List.of(1, 2, 3)) {
            var item = product(quantity, 10_000, 0);
            var result = amounts.calculateProduct(item, List.of(new RefundAmountCalculator.UnitRange(0, quantity)),
                    List.of(), item.netAmountVnd());
            assertEquals(10_000L * quantity, result.amountVnd());
        }

        var item = product(3, 10_000, 0);
        var partial = amounts.calculateProduct(item, List.of(new RefundAmountCalculator.UnitRange(1, 1)),
                List.of(), 30_000);
        assertEquals(10_000, partial.amountVnd());
    }

    @Test
    void allocatesVoucherRemainderAndSequentialDisjointRefundsExactly() {
        var item = product(3, 30_000, 1);
        assertEquals(89_999, item.netAmountVnd());
        var first = amounts.calculateProduct(item, List.of(new RefundAmountCalculator.UnitRange(0, 1)), List.of(), 89_999);
        var second = amounts.calculateProduct(item, List.of(new RefundAmountCalculator.UnitRange(1, 1)), first.requestedRanges(), 59_999);
        var thirdUnavailable = List.of(first.requestedRanges().getFirst(), second.requestedRanges().getFirst());
        var third = amounts.calculateProduct(item, List.of(new RefundAmountCalculator.UnitRange(2, 1)), thirdUnavailable, 29_999);

        assertEquals(30_000, first.amountVnd());
        assertEquals(30_000, second.amountVnd());
        assertEquals(29_999, third.amountVnd());
        assertEquals(item.netAmountVnd(), first.amountVnd() + second.amountVnd() + third.amountVnd());
    }

    @Test
    void rejectsDuplicateOverlapReservedUnitsInvalidRangesAndExcessCap() {
        var item = product(3, 10_000, 0);
        assertThrows(IllegalArgumentException.class, () -> amounts.calculateProduct(item,
                List.of(new RefundAmountCalculator.UnitRange(0, 2), new RefundAmountCalculator.UnitRange(1, 1)),
                List.of(), 30_000));
        assertThrows(IllegalArgumentException.class, () -> amounts.calculateProduct(item,
                List.of(new RefundAmountCalculator.UnitRange(1, 1)),
                List.of(new RefundAmountCalculator.UnitRange(1, 1)), 30_000));
        assertThrows(IllegalArgumentException.class, () -> amounts.calculateProduct(item,
                List.of(new RefundAmountCalculator.UnitRange(1, 1)),
                List.of(new RefundAmountCalculator.UnitRange(0, 2)), 30_000));
        assertThrows(IllegalArgumentException.class, () -> new RefundAmountCalculator.UnitRange(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RefundAmountCalculator.UnitRange(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> amounts.calculateProduct(item,
                List.of(new RefundAmountCalculator.UnitRange(2, 2)), List.of(), 30_000));
        assertThrows(IllegalArgumentException.class, () -> amounts.calculateProduct(item,
                List.of(new RefundAmountCalculator.UnitRange(0, 1)), List.of(), 9_999));
        assertThrows(IllegalArgumentException.class, () -> amounts.calculateProduct(item, List.of(), List.of(), 30_000));
    }

    @Test
    void calculationUsesNetLineAmountAndNeverAddsShipping() {
        var item = product(2, 50_000, 5_000);
        var result = amounts.calculateProduct(item, List.of(new RefundAmountCalculator.UnitRange(0, 2)), List.of(), 95_000);
        assertEquals(95_000, result.amountVnd());
        assertEquals(item.netAmountVnd(), result.amountVnd());
    }

    @Test
    void zeroPricedSnapshotIsAllowedByCurrentOrderRulesButInvalidSnapshotIsRejected() {
        var zeroPriced = product(1, 0, 0);
        assertEquals(0, amounts.calculateProduct(zeroPriced, List.of(new RefundAmountCalculator.UnitRange(0, 1)),
                List.of(), 0).amountVnd());
        var snapshot = new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "Brush", 1, 10_000, true);
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(snapshot, 10_000, 2_000, 9_000));
        assertThrows(IllegalArgumentException.class, () -> amounts.calculateProduct(
                OrderItem.withDiscount(new OrderLineSnapshot(OrderItemType.COURSE, 2, "Course", 1, 1, true), 0),
                List.of(new RefundAmountCalculator.UnitRange(0, 1)), List.of(), 1));
    }

    @Test
    void productEligibilityRequiresSettlementAndAuthoritativePreHandoverEvidence() {
        assertResult(eligibility.evaluateProduct(true, RefundEligibilityPolicy.HandoverState.BEFORE_HANDOVER),
                RefundEligibilityPolicy.Decision.ELIGIBLE, RefundEligibilityPolicy.Reason.PRODUCT_BEFORE_HANDOVER);
        assertResult(eligibility.evaluateProduct(true, RefundEligibilityPolicy.HandoverState.HANDED_OVER),
                RefundEligibilityPolicy.Decision.INELIGIBLE, RefundEligibilityPolicy.Reason.PRODUCT_AFTER_HANDOVER);
        assertResult(eligibility.evaluateProduct(true, RefundEligibilityPolicy.HandoverState.MISSING),
                RefundEligibilityPolicy.Decision.EVIDENCE_UNAVAILABLE, RefundEligibilityPolicy.Reason.HANDOVER_EVIDENCE_MISSING);
        assertResult(eligibility.evaluateProduct(true, RefundEligibilityPolicy.HandoverState.AMBIGUOUS),
                RefundEligibilityPolicy.Decision.EVIDENCE_UNAVAILABLE, RefundEligibilityPolicy.Reason.HANDOVER_EVIDENCE_AMBIGUOUS);
        assertResult(eligibility.evaluateProduct(false, RefundEligibilityPolicy.HandoverState.BEFORE_HANDOVER),
                RefundEligibilityPolicy.Decision.INELIGIBLE, RefundEligibilityPolicy.Reason.ORDER_NOT_SETTLED);
    }

    @Test
    void courseProgressAssignmentAndMissingEvidenceAreEvaluatedIndependently() {
        var start = Instant.parse("2026-01-01T00:00:00Z");
        var now = start.plusSeconds(1);
        assertResult(course(true, new RefundEligibilityPolicy.CourseEvidence(2_000, false), start, now,
                        RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE),
                RefundEligibilityPolicy.Decision.ELIGIBLE, RefundEligibilityPolicy.Reason.COURSE_PROGRESS_WITHIN_LIMIT);
        assertResult(course(true, new RefundEligibilityPolicy.CourseEvidence(2_001, false), start, now,
                        RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE),
                RefundEligibilityPolicy.Decision.INELIGIBLE, RefundEligibilityPolicy.Reason.COURSE_PROGRESS_OVER_LIMIT);
        assertResult(course(true, new RefundEligibilityPolicy.CourseEvidence(0, true), start, now,
                        RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE),
                RefundEligibilityPolicy.Decision.INELIGIBLE, RefundEligibilityPolicy.Reason.COURSE_ASSIGNMENT_SUBMITTED);
        assertResult(course(true, null, start, now, RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE),
                RefundEligibilityPolicy.Decision.EVIDENCE_UNAVAILABLE, RefundEligibilityPolicy.Reason.COURSE_EVIDENCE_MISSING);
        assertResult(course(true, new RefundEligibilityPolicy.CourseEvidence(null, false), start, now,
                        RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE),
                RefundEligibilityPolicy.Decision.EVIDENCE_UNAVAILABLE, RefundEligibilityPolicy.Reason.COURSE_EVIDENCE_MISSING);
        assertResult(course(true, new RefundEligibilityPolicy.CourseEvidence(10_001, false), start, now,
                        RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE),
                RefundEligibilityPolicy.Decision.INVALID_EVIDENCE, RefundEligibilityPolicy.Reason.COURSE_EVIDENCE_INVALID);
    }

    @Test
    void courseWindowUsesCallerOriginEvaluationTimeAndExplicitBoundary() {
        var start = Instant.parse("2026-03-01T10:00:00Z");
        var deadline = start.plus(RefundEligibilityPolicy.COURSE_REFUND_WINDOW);
        var evidence = new RefundEligibilityPolicy.CourseEvidence(1_000, false);
        assertResult(course(true, evidence, start, deadline, RefundEligibilityPolicy.WindowBoundary.DEADLINE_INCLUSIVE),
                RefundEligibilityPolicy.Decision.ELIGIBLE, RefundEligibilityPolicy.Reason.COURSE_PROGRESS_WITHIN_LIMIT);
        assertResult(course(true, evidence, start, deadline, RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE),
                RefundEligibilityPolicy.Decision.INELIGIBLE, RefundEligibilityPolicy.Reason.COURSE_WINDOW_EXPIRED);
        assertResult(course(true, evidence, start, start.minusNanos(1), RefundEligibilityPolicy.WindowBoundary.DEADLINE_INCLUSIVE),
                RefundEligibilityPolicy.Decision.INELIGIBLE, RefundEligibilityPolicy.Reason.COURSE_WINDOW_NOT_STARTED);
        assertResult(course(false, evidence, start, start, RefundEligibilityPolicy.WindowBoundary.DEADLINE_INCLUSIVE),
                RefundEligibilityPolicy.Decision.INELIGIBLE, RefundEligibilityPolicy.Reason.ORDER_NOT_SETTLED);
    }

    @Test
    void productHandoverDoesNotChangeIndependentCourseDecisionForMixedOrder() {
        var product = eligibility.evaluateProduct(true, RefundEligibilityPolicy.HandoverState.HANDED_OVER);
        var course = course(true, new RefundEligibilityPolicy.CourseEvidence(1_500, false),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"),
                RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE);
        assertEquals(RefundEligibilityPolicy.Decision.INELIGIBLE, product.decision());
        assertEquals(RefundEligibilityPolicy.Decision.ELIGIBLE, course.decision());
    }

    private RefundEligibilityPolicy.Result course(boolean settled, RefundEligibilityPolicy.CourseEvidence evidence,
                                                  Instant start, Instant at, RefundEligibilityPolicy.WindowBoundary boundary) {
        return eligibility.evaluateCourse(settled, evidence, start, at, boundary);
    }

    private static void assertResult(RefundEligibilityPolicy.Result result, RefundEligibilityPolicy.Decision decision,
                                     RefundEligibilityPolicy.Reason reason) {
        assertEquals(decision, result.decision());
        assertEquals(reason, result.reason());
    }

    private static OrderItem product(int quantity, long unitPrice, long discount) {
        var snapshot = new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "Brush set", quantity, unitPrice, true);
        return OrderItem.withDiscount(snapshot, discount);
    }
}
