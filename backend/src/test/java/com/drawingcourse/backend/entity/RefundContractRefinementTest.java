package com.drawingcourse.backend.entity;

import com.drawingcourse.backend.integration.contract.CourseRefundContracts;
import com.drawingcourse.backend.integration.contract.ProductRefundContracts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.drawingcourse.backend.entity.RefundStateMachine.*;
import static org.junit.jupiter.api.Assertions.*;

class RefundContractRefinementTest {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final RefundScope SCOPE = new RefundScope(11, 22);

    @Test
    void courseProgressAssignmentAndBoundaryRemainExplicit() {
        var policy = new RefundEligibilityPolicy();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant deadline = start.plus(RefundEligibilityPolicy.COURSE_REFUND_WINDOW);

        assertEquals(RefundEligibilityPolicy.Decision.ELIGIBLE,
                policy.evaluateCourse(true, new RefundEligibilityPolicy.CourseEvidence(2_000, false),
                        start, deadline, RefundEligibilityPolicy.WindowBoundary.DEADLINE_INCLUSIVE).decision());
        assertEquals(RefundEligibilityPolicy.Reason.COURSE_PROGRESS_OVER_LIMIT,
                policy.evaluateCourse(true, new RefundEligibilityPolicy.CourseEvidence(2_001, false),
                        start, deadline.minusNanos(1), RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE).reason());
        assertEquals(RefundEligibilityPolicy.Reason.COURSE_ASSIGNMENT_SUBMITTED,
                policy.evaluateCourse(true, new RefundEligibilityPolicy.CourseEvidence(0, true),
                        start, start, RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE).reason());
        assertEquals(RefundEligibilityPolicy.Decision.INELIGIBLE,
                policy.evaluateCourse(true, new RefundEligibilityPolicy.CourseEvidence(2_000, false),
                        start, deadline, RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE).decision());
    }

    @Test
    void productCutoffIsCarrierHandoverAndUnknownFailsClosed() {
        var policy = new RefundEligibilityPolicy();
        assertEquals(RefundEligibilityPolicy.Decision.ELIGIBLE,
                policy.evaluateProduct(true, RefundEligibilityPolicy.HandoverState.BEFORE_HANDOVER).decision());
        assertEquals(RefundEligibilityPolicy.Reason.PRODUCT_AFTER_HANDOVER,
                policy.evaluateProduct(true, RefundEligibilityPolicy.HandoverState.HANDED_OVER).reason());

        var scope = new ProductRefundContracts.RefundScope(1, 2, 3, 4L);
        var unknown = new ProductRefundContracts.HandoverEvidence(scope, "shipment-1",
                ProductRefundContracts.HandoverState.UNKNOWN, null, Instant.parse("2026-01-01T00:00:00Z"),
                "handover-evidence-1", 1);
        assertThrows(IllegalStateException.class, unknown::requireBeforeHandover);
    }

    @Test
    void partialProductSelectionAllocationAndOverlapAreDeterministic() {
        var selected = RefundRequestAccounting.selectProductUnits(5, 2, List.of(3, 1));
        assertEquals(List.of(0, 2), selected);
        assertThrows(IllegalArgumentException.class,
                () -> RefundRequestAccounting.requireNoUnitOverlap(selected, List.of(4, 2)));

        OrderItem item = OrderItem.withDiscount(
                new OrderLineSnapshot(OrderItemType.PRODUCT, 7, "Brush", 3, 50_000, true), 10_001);
        var result = new RefundAmountCalculator().calculateProduct(item,
                List.of(new RefundAmountCalculator.UnitRange(0, 1),
                        new RefundAmountCalculator.UnitRange(2, 1)), List.of(), item.netAmountVnd());
        assertEquals(item.netAmountForUnits(0, 1) + item.netAmountForUnits(2, 1), result.amountVnd());
    }

    @Test
    void comboEligibilityIsAllOrNothingAndReportsFirstFailure() {
        var policy = new RefundEligibilityPolicy();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        var valid = policy.evaluateCombo(true, List.of(
                        new RefundEligibilityPolicy.CourseEvidence(0, false),
                        new RefundEligibilityPolicy.CourseEvidence(2_000, false)),
                start, start.plusSeconds(1), RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE);
        assertEquals(RefundEligibilityPolicy.Decision.ELIGIBLE, valid.decision());

        var rejected = policy.evaluateCombo(true, List.of(
                        new RefundEligibilityPolicy.CourseEvidence(0, false),
                        new RefundEligibilityPolicy.CourseEvidence(100, true),
                        new RefundEligibilityPolicy.CourseEvidence(0, false)),
                start, start.plusSeconds(1), RefundEligibilityPolicy.WindowBoundary.DEADLINE_EXCLUSIVE);
        assertEquals(RefundEligibilityPolicy.Decision.INELIGIBLE, rejected.decision());
        assertEquals(1, rejected.failingComponentIndex());
        assertEquals(RefundEligibilityPolicy.Reason.COURSE_ASSIGNMENT_SUBMITTED, rejected.reason());
        assertEquals(250_000, RefundRequestAccounting.wholeComboAmount(1, 250_000, 0, 250_000, false));
        assertThrows(IllegalArgumentException.class,
                () -> RefundRequestAccounting.wholeComboAmount(1, 250_000, 1, 249_999, true));
    }

    @Test
    void courseAndComboPhysicalEffectsRemainIndependentFromCourseAccess() {
        State course = requestCourseWithPhysicalGifts(SCOPE, true, 50_000,
                new HoldAcquisitionEvidence(Evidence.CONFIRMED)).state().orElseThrow();
        State approved = approve(course, event(OperationType.ADMIN_APPROVAL, "approve", HASH_A),
                new CourseWithPhysicalGiftApprovalEvidence(Evidence.CONFIRMED, Evidence.CONFIRMED)).state();
        assertEquals(FulfillmentStatus.ADJUSTED, approved.fulfillment());
        assertEquals(CourseAccessStatus.ELIGIBILITY_CONFIRMED, approved.courseAccess());

        State processing = beginFinancialExecution(approved,
                event(OperationType.FINANCIAL_EXECUTION_START, "execute", HASH_B)).state();
        State paid = applyProviderEvidence(processing, provider("provider-success", HASH_A)).state();
        assertEquals(CourseAccessStatus.REVOCATION_PENDING, paid.courseAccess());
        assertFalse(paid.effectsComplete());

        State holdReleased = releaseHoldAfterAdjustment(paid,
                event(OperationType.HOLD_RELEASE_AFTER_ADJUSTMENT, "release", HASH_B),
                new HoldReleaseEvidence(Evidence.CONFIRMED)).state();
        State revoked = confirmCourseAccessRevoked(holdReleased,
                event(OperationType.COURSE_ACCESS_REVOCATION, "revoke", HASH_A), Evidence.CONFIRMED).state();
        assertTrue(revoked.effectsComplete());

        State combo = requestCombo(new RefundScope(12, 23), true, 100_000, true,
                new HoldAcquisitionEvidence(Evidence.CONFIRMED)).state().orElseThrow();
        assertEquals(ItemType.COMBO, combo.itemType());
        assertTrue(combo.physicalEffectsRequired());
        assertTrue(combo.courseAccessEffectsRequired());
    }

    @Test
    void typedEventReplayIsIdempotentAndChangedPayloadConflicts() {
        State requested = requestCourse(SCOPE, true, 20_000).state().orElseThrow();
        EventIdentity approval = event(OperationType.ADMIN_APPROVAL, "approval-1", HASH_A);
        State approved = approve(requested, approval, new CourseApprovalEvidence(Evidence.CONFIRMED)).state();
        assertEquals(Outcome.ALREADY_APPLIED,
                approve(approved, approval, new CourseApprovalEvidence(Evidence.CONFIRMED)).outcome());
        TransitionResult conflict = approve(approved,
                event(OperationType.ADMIN_APPROVAL, "approval-1", HASH_B),
                new CourseApprovalEvidence(Evidence.CONFIRMED));
        assertEquals(Outcome.REJECTED, conflict.outcome());
        assertEquals(Reason.EVENT_IDENTITY_CONFLICT, conflict.reason());
    }

    @Test
    void requestFingerprintIsCanonicalVersionedAndDetectsConflict() {
        var first = new RefundRequestAccounting.RequestIdentity(1, 2, 3,
                RefundRequestAccounting.ScopeType.PRODUCT_UNITS, List.of(2, 0), "DAMAGED");
        var replay = new RefundRequestAccounting.RequestIdentity(1, 2, 3,
                RefundRequestAccounting.ScopeType.PRODUCT_UNITS, List.of(0, 2), "DAMAGED");
        var conflict = new RefundRequestAccounting.RequestIdentity(1, 2, 3,
                RefundRequestAccounting.ScopeType.PRODUCT_UNITS, List.of(0, 2), "WRONG_ITEM");
        assertEquals(first.fingerprint(), replay.fingerprint());
        assertEquals(RefundRequestAccounting.FingerprintComparison.IDEMPOTENT_REPLAY,
                RefundRequestAccounting.compareFingerprint(first.fingerprint(), replay.fingerprint()));
        assertEquals(RefundRequestAccounting.FingerprintComparison.CONFLICT,
                RefundRequestAccounting.compareFingerprint(first.fingerprint(), conflict.fingerprint()));
    }

    @Test
    void unknownRetainsReservationAndCapturedCapIsEnforced() {
        var obligations = List.of(
                obligation(100, RefundRequestAccounting.ObligationStatus.RESERVED),
                obligation(200, RefundRequestAccounting.ObligationStatus.PROCESSING),
                obligation(300, RefundRequestAccounting.ObligationStatus.UNKNOWN),
                obligation(100, RefundRequestAccounting.ObligationStatus.SUCCEEDED),
                obligation(50, RefundRequestAccounting.ObligationStatus.RELEASED),
                obligation(50, RefundRequestAccounting.ObligationStatus.DEFINITIVELY_FAILED));
        assertEquals(700, RefundRequestAccounting.reservedOrRefundedAmount(obligations));
        assertEquals(300, RefundRequestAccounting.remainingRefundableAmount(1_000, obligations));
        assertDoesNotThrow(() -> RefundRequestAccounting.requireWithinRemainingCap(300, 400, 300));
        assertThrows(IllegalArgumentException.class,
                () -> RefundRequestAccounting.requireWithinRemainingCap(301, 400, 300));
    }

    @Test
    void courseEvidenceIsCompleteOrderedImmutableAndFailsClosedWithoutConsistency() {
        var scope = new CourseRefundContracts.RefundScope(1, 2, 3);
        Instant observed = Instant.parse("2026-01-01T00:00:00Z");
        var evidence = new CourseRefundContracts.CourseRefundEvidence(scope,
                CourseRefundContracts.SourceType.WHOLE_COMBO, List.of(
                new CourseRefundContracts.CourseEvidence(10L, 0, 0, false,
                        observed, "course-10-v1", 1),
                new CourseRefundContracts.CourseEvidence(11L, 1, 2_000, false,
                        observed, "course-11-v2", 2)),
                CourseRefundContracts.ConsistencyGuarantee.UNSUPPORTED, null);
        assertThrows(IllegalStateException.class, evidence::requireDecisionSafe);
        assertThrows(UnsupportedOperationException.class,
                () -> evidence.orderedCourses().add(evidence.orderedCourses().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new CourseRefundContracts.CourseRefundEvidence(scope,
                CourseRefundContracts.SourceType.WHOLE_COMBO, List.of(
                new CourseRefundContracts.CourseEvidence(10L, 1, 0, false,
                        observed, "bad-position", 1)),
                CourseRefundContracts.ConsistencyGuarantee.REVALIDATED_AT_OBSERVATION, null));
    }

    private static RefundRequestAccounting.AmountObligation obligation(long amount,
                                                                        RefundRequestAccounting.ObligationStatus status) {
        return new RefundRequestAccounting.AmountObligation(amount, status);
    }

    private static EventIdentity event(OperationType type, String key, String hash) {
        return new EventIdentity(type, SCOPE, key, hash);
    }

    private static ProviderEvidence provider(String key, String hash) {
        return new ProviderEvidence(event(OperationType.PROVIDER_OUTCOME, key, hash),
                ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.SUCCESS,
                Evidence.CONFIRMED, Evidence.CONFIRMED, Evidence.CONFIRMED);
    }
}
