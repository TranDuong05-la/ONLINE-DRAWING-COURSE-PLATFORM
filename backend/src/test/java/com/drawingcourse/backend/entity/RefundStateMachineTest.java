package com.drawingcourse.backend.entity;

import org.junit.jupiter.api.Test;

import static com.drawingcourse.backend.entity.RefundStateMachine.*;
import static org.junit.jupiter.api.Assertions.*;

class RefundStateMachineTest {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final RefundScope SCOPE = new RefundScope(10, 20);

    @Test
    void adminCanApproveOrRejectOnceAndCannotReverseDecision() {
        State pending = product(5_000);
        EventIdentity approval = event(OperationType.ADMIN_APPROVAL, "admin-1", HASH_A);
        TransitionResult approved = approve(pending, approval, productApproval());
        assertEquals(Outcome.APPLIED, approved.outcome());
        assertEquals(AdminDecision.APPROVED, approved.state().decision());
        assertEquals(Outcome.ALREADY_APPLIED, approve(approved.state(), approval, productApproval()).outcome());
        assertEquals(Outcome.ALREADY_APPLIED,
                approve(approved.state(), event(OperationType.ADMIN_APPROVAL, "admin-retry", HASH_B), productApproval()).outcome());
        TransitionResult opposite = reject(approved.state(), event(OperationType.ADMIN_REJECTION, "reject", HASH_A), holdRelease());
        assertEquals(Outcome.REJECTED, opposite.outcome());
        assertEquals(AdminDecision.APPROVED, opposite.state().decision());

        TransitionResult rejected = reject(product(5_000), event(OperationType.ADMIN_REJECTION, "reject-1", HASH_A), holdRelease());
        assertEquals(AdminDecision.REJECTED, rejected.state().decision());
        assertEquals(FulfillmentStatus.RELEASED, rejected.state().fulfillment());
        assertEquals(HoldStatus.RELEASED, rejected.state().hold());
    }

    @Test
    void productApprovalNeedsAuthoritativeAdjustmentAndCanKeepHoldActive() {
        State pending = product(5_000);
        TransitionResult missing = approve(pending, event(OperationType.ADMIN_APPROVAL, "approve", HASH_A), new ProductApprovalEvidence(Evidence.MISSING));
        assertEquals(Outcome.REJECTED, missing.outcome());
        assertEquals(AdminDecision.REQUESTED, missing.state().decision());

        State adjusted = approve(pending, event(OperationType.ADMIN_APPROVAL, "approve", HASH_A), productApproval()).state();
        assertEquals(FulfillmentStatus.ADJUSTED, adjusted.fulfillment());
        assertEquals(HoldStatus.ACTIVE, adjusted.hold());
        assertEquals(FinancialStatus.READY, adjusted.financial());
    }

    @Test
    void holdReleaseAfterAdjustmentIsIndependentFromFulfillmentAndFinancialSuccess() {
        State adjusted = approve(product(5_000), event(OperationType.ADMIN_APPROVAL, "approve", HASH_A), productApproval()).state();
        TransitionResult missing = releaseHoldAfterAdjustment(adjusted, event(OperationType.HOLD_RELEASE_AFTER_ADJUSTMENT, "release", HASH_A),
                new HoldReleaseEvidence(Evidence.AMBIGUOUS));
        assertEquals(Outcome.REJECTED, missing.outcome());
        State released = releaseHoldAfterAdjustment(adjusted, event(OperationType.HOLD_RELEASE_AFTER_ADJUSTMENT, "release", HASH_A), holdRelease()).state();
        assertEquals(FulfillmentStatus.ADJUSTED, released.fulfillment());
        assertEquals(HoldStatus.RELEASED, released.hold());
        assertEquals(FinancialStatus.READY, released.financial());
    }

    @Test
    void productRejectionRequiresConfirmedHoldRelease() {
        State pending = product(5_000);
        TransitionResult missing = reject(pending, event(OperationType.ADMIN_REJECTION, "reject", HASH_A), new HoldReleaseEvidence(Evidence.MISSING));
        assertEquals(Outcome.REJECTED, missing.outcome());
        assertEquals(AdminDecision.REQUESTED, missing.state().decision());
        State rejected = reject(pending, event(OperationType.ADMIN_REJECTION, "reject", HASH_A), holdRelease()).state();
        assertEquals(AdminDecision.REJECTED, rejected.decision());
        assertEquals(HoldStatus.RELEASED, rejected.hold());
        assertEquals(FinancialStatus.NOT_STARTED, rejected.financial());
    }

    @Test
    void courseApprovalRequiresEligibilityAndNeverUsesShipmentHold() {
        State pending = requestCourse(SCOPE, true, 9_000).state().orElseThrow();
        assertEquals(FulfillmentStatus.NOT_REQUIRED, pending.fulfillment());
        assertEquals(HoldStatus.NOT_APPLICABLE, pending.hold());
        assertEquals(Outcome.REJECTED, approve(pending, event(OperationType.ADMIN_APPROVAL, "course-approve", HASH_A),
                new CourseApprovalEvidence(Evidence.MISSING)).outcome());
        State approved = approve(pending, event(OperationType.ADMIN_APPROVAL, "course-approve", HASH_A), courseApproval()).state();
        assertTrue(approved.courseEligibilityConfirmed());
        assertEquals(FinancialStatus.READY, approved.financial());
    }

    @Test
    void financialExecutionRequiresApprovalAndVerifiedSuccessEvidence() {
        State pending = product(3_000);
        assertEquals(Outcome.REJECTED, beginFinancialExecution(pending, event(OperationType.FINANCIAL_EXECUTION_START, "start", HASH_A)).outcome());
        State processing = beginFinancialExecution(
                approve(pending, event(OperationType.ADMIN_APPROVAL, "approve", HASH_A), productApproval()).state(), event(OperationType.FINANCIAL_EXECUTION_START, "start", HASH_B)).state();
        ProviderEvidence evidence = provider("success-1", HASH_A, ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.SUCCESS,
                Evidence.CONFIRMED, Evidence.CONFIRMED, Evidence.CONFIRMED);
        State succeeded = applyProviderEvidence(processing, evidence).state();
        assertEquals(FinancialStatus.SUCCEEDED, succeeded.financial());
        assertEquals(Outcome.ALREADY_APPLIED, applyProviderEvidence(succeeded, evidence).outcome());
        TransitionResult conflict = applyProviderEvidence(succeeded, provider("success-2", HASH_B,
                ProviderEvidenceKind.INITIAL_RESULT,
                ProviderOutcome.SUCCESS, Evidence.CONFIRMED, Evidence.CONFIRMED, Evidence.CONFIRMED));
        assertEquals(Outcome.REJECTED, conflict.outcome());
        assertEquals(FinancialStatus.SUCCEEDED, conflict.state().financial());
    }

    @Test
    void unknownOutcomeBlocksExecutionUntilVerifiedReconciliation() {
        State processing = beginFinancialExecution(approvedProduct(2_000), event(OperationType.FINANCIAL_EXECUTION_START, "start", HASH_A)).state();
        State unknown = markUnknown(processing, event(OperationType.LOCAL_PROVIDER_TIMEOUT, "timeout", HASH_B)).state();
        assertEquals(FinancialStatus.UNKNOWN, unknown.financial());
        assertTrue(unknown.reviewRequired());
        assertEquals(Outcome.REJECTED, beginFinancialExecution(unknown, event(OperationType.FINANCIAL_EXECUTION_START, "duplicate-start", HASH_A)).outcome());
        assertEquals(Outcome.REJECTED, applyProviderEvidence(unknown, provider("initial-after-unknown", HASH_A,
                ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.SUCCESS)).outcome());
        State reconciled = applyProviderEvidence(unknown, provider("reconcile-success", HASH_A,
                ProviderEvidenceKind.RECONCILIATION, ProviderOutcome.SUCCESS)).state();
        assertEquals(FinancialStatus.SUCCEEDED, reconciled.financial());
        assertFalse(reconciled.reviewRequired());
    }

    @Test
    void definitiveFailureAfterAdjustmentRetainsReviewAndDoesNotFreePhysicalState() {
        State processing = beginFinancialExecution(approvedProduct(2_000), event(OperationType.FINANCIAL_EXECUTION_START, "start", HASH_A)).state();
        State failed = applyProviderEvidence(processing, provider("failure", HASH_B,
                ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.DEFINITIVE_FAILURE)).state();
        assertEquals(FinancialStatus.FAILED, failed.financial());
        assertTrue(failed.reviewRequired());
        assertEquals(FulfillmentStatus.ADJUSTED, failed.fulfillment());
        assertEquals(HoldStatus.ACTIVE, failed.hold());
    }

    @Test
    void invalidOrConflictingProviderEvidenceIsRejectedWithoutChangingState() {
        State processing = beginFinancialExecution(approvedProduct(2_000), event(OperationType.FINANCIAL_EXECUTION_START, "start", HASH_A)).state();
        TransitionResult mismatch = applyProviderEvidence(processing, provider("bad-amount", HASH_B,
                ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.SUCCESS, Evidence.CONFIRMED, Evidence.REJECTED, Evidence.CONFIRMED));
        assertEquals(Reason.PROVIDER_AMOUNT_MISMATCH, mismatch.reason());
        assertEquals(FinancialStatus.PROCESSING, mismatch.state().financial());
        TransitionResult ambiguous = applyProviderEvidence(processing, provider("ambiguous", HASH_B,
                ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.AMBIGUOUS, Evidence.CONFIRMED, Evidence.CONFIRMED, Evidence.CONFIRMED));
        assertEquals(Reason.PROVIDER_EVENT_AMBIGUOUS, ambiguous.reason());
        assertEquals(FinancialStatus.PROCESSING, ambiguous.state().financial());
    }

    @Test
    void sameEventIdentityWithDifferentPayloadIsConflictingReplay() {
        State pending = product(1_000);
        State approved = approve(pending, event(OperationType.ADMIN_APPROVAL, "admin-event", HASH_A), productApproval()).state();
        TransitionResult conflict = approve(approved, event(OperationType.ADMIN_APPROVAL, "admin-event", HASH_B), productApproval());
        assertEquals(Outcome.REJECTED, conflict.outcome());
        assertEquals(Reason.EVENT_IDENTITY_CONFLICT, conflict.reason());
    }

    @Test
    void mixedCourseAndProductItemsKeepIndependentFulfillmentAxes() {
        State productApproved = approvedProduct(1_000);
        State courseApproved = approve(requestCourse(SCOPE, true, 1_000).state().orElseThrow(),
                event(OperationType.ADMIN_APPROVAL, "course-approve", HASH_B), courseApproval()).state();
        assertEquals(HoldStatus.ACTIVE, productApproved.hold());
        assertEquals(FulfillmentStatus.ADJUSTED, productApproved.fulfillment());
        assertEquals(HoldStatus.NOT_APPLICABLE, courseApproved.hold());
        assertEquals(FulfillmentStatus.NOT_REQUIRED, courseApproved.fulfillment());
    }

    @Test
    void unknownTimeoutAndVerifiedUnknownBothRequireReviewAndReplayIdempotently() {
        State processing = beginFinancialExecution(approvedProduct(2_000),
                event(OperationType.FINANCIAL_EXECUTION_START, "start", HASH_A)).state();
        EventIdentity timeout = event(OperationType.LOCAL_PROVIDER_TIMEOUT, "timeout", HASH_B);
        TransitionResult firstTimeout = markUnknown(processing, timeout);
        assertEquals(FinancialStatus.UNKNOWN, firstTimeout.state().financial());
        assertTrue(firstTimeout.state().reviewRequired());
        assertEquals(Outcome.ALREADY_APPLIED, markUnknown(firstTimeout.state(), timeout).outcome());
        assertTrue(markUnknown(firstTimeout.state(), timeout).state().reviewRequired());

        ProviderEvidence unknownEvidence = provider("provider-unknown", HASH_A,
                ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.UNKNOWN);
        TransitionResult providerUnknown = applyProviderEvidence(processing, unknownEvidence);
        assertEquals(FinancialStatus.UNKNOWN, providerUnknown.state().financial());
        assertTrue(providerUnknown.state().reviewRequired());
        assertEquals(Outcome.ALREADY_APPLIED, applyProviderEvidence(providerUnknown.state(), unknownEvidence).outcome());
        TransitionResult conflictingReplay = applyProviderEvidence(providerUnknown.state(), provider("provider-unknown", HASH_B,
                ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.UNKNOWN));
        assertEquals(Reason.EVENT_IDENTITY_CONFLICT, conflictingReplay.reason());
        assertTrue(conflictingReplay.state().reviewRequired());
    }

    @Test
    void sameRawEventIdIsSeparatedByOperationButScopeCannotCrossObligations() {
        State processing = beginFinancialExecution(approvedProduct(2_000),
                event(OperationType.FINANCIAL_EXECUTION_START, "shared-id", HASH_A)).state();
        ProviderEvidence evidence = provider("shared-id", HASH_B, ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.UNKNOWN);
        TransitionResult result = applyProviderEvidence(processing, evidence);
        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(FinancialStatus.UNKNOWN, result.state().financial());
        assertTrue(result.state().reviewRequired());

        EventIdentity foreignScope = new EventIdentity(OperationType.PROVIDER_OUTCOME,
                new RefundScope(11, 21), "another", HASH_A);
        TransitionResult foreign = applyProviderEvidence(result.state(), new ProviderEvidence(foreignScope,
                ProviderEvidenceKind.RECONCILIATION, ProviderOutcome.SUCCESS,
                Evidence.CONFIRMED, Evidence.CONFIRMED, Evidence.CONFIRMED));
        assertEquals(Reason.INVALID_EVENT_IDENTITY, foreign.reason());
        assertEquals(FinancialStatus.UNKNOWN, foreign.state().financial());
        assertTrue(foreign.state().reviewRequired());
    }

    @Test
    void successCannotRegressAndConflictingEvidenceCannotClearReview() {
        State processing = beginFinancialExecution(approvedProduct(2_000),
                event(OperationType.FINANCIAL_EXECUTION_START, "start", HASH_A)).state();
        State succeeded = applyProviderEvidence(processing, provider("paid", HASH_B,
                ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.SUCCESS)).state();
        TransitionResult failure = applyProviderEvidence(succeeded, provider("contradictory-failure", HASH_A,
                ProviderEvidenceKind.RECONCILIATION, ProviderOutcome.DEFINITIVE_FAILURE));
        assertEquals(Outcome.REJECTED, failure.outcome());
        assertEquals(FinancialStatus.SUCCEEDED, failure.state().financial());
        assertFalse(failure.state().reviewRequired());

        State unknown = applyProviderEvidence(processing, provider("unknown", HASH_A,
                ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.UNKNOWN)).state();
        TransitionResult collision = applyProviderEvidence(unknown, provider("unknown", HASH_B,
                ProviderEvidenceKind.INITIAL_RESULT, ProviderOutcome.UNKNOWN));
        assertEquals(Reason.EVENT_IDENTITY_CONFLICT, collision.reason());
        assertTrue(collision.state().reviewRequired());
    }

    @Test
    void missingFulfillmentEvidenceCannotAdvanceProductAndCourseNeverNeedsShipmentHold() {
        State requested = product(2_000);
        TransitionResult missing = approve(requested, event(OperationType.ADMIN_APPROVAL, "missing-adjustment", HASH_A),
                new ProductApprovalEvidence(Evidence.MISSING));
        assertEquals(Outcome.REJECTED, missing.outcome());
        assertEquals(FulfillmentStatus.PENDING, missing.state().fulfillment());
        assertEquals(HoldStatus.ACTIVE, missing.state().hold());

        State course = requestCourse(SCOPE, true, 2_000).state().orElseThrow();
        State approvedCourse = approve(course, event(OperationType.ADMIN_APPROVAL, "course", HASH_A), courseApproval()).state();
        assertEquals(HoldStatus.NOT_APPLICABLE, approvedCourse.hold());
        assertEquals(FulfillmentStatus.NOT_REQUIRED, approvedCourse.fulfillment());
    }

    @Test
    void unsettledAndZeroNetRulesDoNotFabricateFinancialExecution() {
        assertEquals(Reason.UNSETTLED_ORDER, requestProduct(SCOPE, false, 1_000,
                new HoldAcquisitionEvidence(Evidence.CONFIRMED)).reason());
        State zero = approve(product(0), event(OperationType.ADMIN_APPROVAL, "approve-zero", HASH_A), productApproval()).state();
        assertEquals(AdminDecision.APPROVED, zero.decision());
        assertEquals(FinancialStatus.NOT_STARTED, zero.financial());
    }

    private static State product(long amount) {
        return requestProduct(SCOPE, true, amount, new HoldAcquisitionEvidence(Evidence.CONFIRMED)).state().orElseThrow();
    }
    private static State approvedProduct(long amount) {
        return approve(product(amount), event(OperationType.ADMIN_APPROVAL, "approve", HASH_A), productApproval()).state();
    }
    private static ProductApprovalEvidence productApproval() { return new ProductApprovalEvidence(Evidence.CONFIRMED); }
    private static CourseApprovalEvidence courseApproval() { return new CourseApprovalEvidence(Evidence.CONFIRMED); }
    private static HoldReleaseEvidence holdRelease() { return new HoldReleaseEvidence(Evidence.CONFIRMED); }
    private static EventIdentity event(OperationType operation, String key, String digest) { return new EventIdentity(operation, SCOPE, key, digest); }
    private static ProviderEvidence provider(String key, String digest, ProviderEvidenceKind kind, ProviderOutcome outcome) {
        return provider(key, digest, kind, outcome, Evidence.CONFIRMED, Evidence.CONFIRMED, Evidence.CONFIRMED);
    }
    private static ProviderEvidence provider(String key, String digest, ProviderEvidenceKind kind, ProviderOutcome outcome,
                                             Evidence signature, Evidence amount, Evidence currency) {
        return new ProviderEvidence(event(OperationType.PROVIDER_OUTCOME, key, digest), kind, outcome, signature, amount, currency);
    }
}
