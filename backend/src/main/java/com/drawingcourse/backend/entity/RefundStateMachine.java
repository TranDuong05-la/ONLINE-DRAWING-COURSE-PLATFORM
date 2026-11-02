package com.drawingcourse.backend.entity;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Pure, immutable refund workflow model. It records decisions and evidence claims only;
 * it does not persist reservations, acquire a Shipment HOLD, or verify provider signatures.
 */
public final class RefundStateMachine {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private RefundStateMachine() { }

    public enum ItemType { COURSE, PRODUCT, COMBO }
    public enum AdminDecision { REQUESTED, APPROVED, REJECTED }
    public enum FulfillmentStatus { NOT_REQUIRED, PENDING, ADJUSTED, RELEASED, REQUIRES_REVIEW }
    public enum HoldStatus { NOT_APPLICABLE, ACTIVE, RELEASED, REQUIRES_REVIEW }
    public enum CourseAccessStatus {
        NOT_APPLICABLE, UNCHANGED, ELIGIBILITY_CONFIRMED, REVOCATION_PENDING, REVOKED, REQUIRES_REVIEW
    }
    public enum FinancialStatus { NOT_STARTED, READY, PROCESSING, UNKNOWN, SUCCEEDED, FAILED, REQUIRES_REVIEW }
    public enum Outcome { APPLIED, ALREADY_APPLIED, REJECTED }
    public enum Reason {
        NONE, UNSETTLED_ORDER, INVALID_AMOUNT, HOLD_NOT_CONFIRMED, FULFILLMENT_ADJUSTMENT_NOT_CONFIRMED,
        HOLD_RELEASE_NOT_CONFIRMED, COURSE_ELIGIBILITY_NOT_CONFIRMED, FULFILLMENT_NOT_ADJUSTED,
        DECISION_ALREADY_REJECTED,
        DECISION_ALREADY_APPROVED, FINANCIAL_NOT_READY, UNKNOWN_REQUIRES_RECONCILIATION,
        PROVIDER_EVIDENCE_UNVERIFIED, PROVIDER_AMOUNT_MISMATCH, PROVIDER_CURRENCY_MISMATCH,
        PROVIDER_EVENT_AMBIGUOUS, PROVIDER_OUTCOME_CONFLICT, SUCCESS_ALREADY_RECORDED,
        EVENT_IDENTITY_CONFLICT, INVALID_EVENT_IDENTITY, COURSE_ACCESS_REVOCATION_NOT_CONFIRMED
    }
    public enum OperationType {
        ADMIN_APPROVAL, ADMIN_REJECTION, HOLD_RELEASE_AFTER_ADJUSTMENT,
        FINANCIAL_EXECUTION_START, LOCAL_PROVIDER_TIMEOUT, PROVIDER_OUTCOME, COURSE_ACCESS_REVOCATION
    }
    public enum Evidence { CONFIRMED, MISSING, AMBIGUOUS, REJECTED }
    public enum ProviderOutcome { SUCCESS, DEFINITIVE_FAILURE, UNKNOWN, AMBIGUOUS }
    public enum ReviewReason { PROVIDER_OUTCOME_UNKNOWN, PHYSICAL_ADJUSTMENT_WITH_PROVIDER_FAILURE }

    public sealed interface ApprovalEvidence permits ProductApprovalEvidence, CourseApprovalEvidence,
            CourseWithPhysicalGiftApprovalEvidence { }
    public record ProductApprovalEvidence(Evidence fulfillmentAdjustment) implements ApprovalEvidence {
        public ProductApprovalEvidence { Objects.requireNonNull(fulfillmentAdjustment); }
    }
    public record CourseApprovalEvidence(Evidence eligibility) implements ApprovalEvidence {
        public CourseApprovalEvidence { Objects.requireNonNull(eligibility); }
    }
    public record CourseWithPhysicalGiftApprovalEvidence(Evidence eligibility,
                                                         Evidence fulfillmentAdjustment)
            implements ApprovalEvidence {
        public CourseWithPhysicalGiftApprovalEvidence {
            Objects.requireNonNull(eligibility);
            Objects.requireNonNull(fulfillmentAdjustment);
        }
    }
    public record HoldAcquisitionEvidence(Evidence result) {
        public HoldAcquisitionEvidence { Objects.requireNonNull(result); }
    }
    public record HoldReleaseEvidence(Evidence result) {
        public HoldReleaseEvidence { Objects.requireNonNull(result); }
    }

    /** D-owned obligation identity; identifiers are positive persistent IDs. */
    public record RefundScope(int requestId, int itemId) {
        public RefundScope {
            if (requestId <= 0 || itemId <= 0) throw new IllegalArgumentException("Refund scope IDs must be positive");
        }
    }

    /** Typed canonical event identity. stableEventId is locally derived when the provider has no stable ID. */
    public record EventIdentity(OperationType operationType, RefundScope scope,
                                String stableEventId, String payloadSha256) {
        public EventIdentity {
            Objects.requireNonNull(operationType, "operationType");
            Objects.requireNonNull(scope, "scope");
            if (stableEventId == null || stableEventId.isBlank() || stableEventId.length() > 128
                    || payloadSha256 == null || !SHA_256.matcher(payloadSha256).matches()) {
                throw new IllegalArgumentException("Event identity requires a bounded stable ID and SHA-256 payload digest");
            }
            stableEventId = stableEventId.trim();
            payloadSha256 = payloadSha256.toLowerCase(java.util.Locale.ROOT);
        }
        private EventKey key() { return new EventKey(operationType, scope, stableEventId); }
    }
    private record EventKey(OperationType operationType, RefundScope scope, String stableEventId) { }

    /** Explicitly supplied evidence. Authenticity remains the responsibility of the caller boundary. */
    public enum ProviderEvidenceKind { INITIAL_RESULT, RECONCILIATION }

    public record ProviderEvidence(EventIdentity event, ProviderEvidenceKind kind, ProviderOutcome outcome, Evidence signature,
                                   Evidence amountMatch, Evidence currencyMatch) {
        public ProviderEvidence {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(kind, "kind");
            if (event.operationType() != OperationType.PROVIDER_OUTCOME)
                throw new IllegalArgumentException("Provider evidence requires PROVIDER_OUTCOME identity");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(amountMatch, "amountMatch");
            Objects.requireNonNull(currencyMatch, "currencyMatch");
        }
    }

    /** Constructor is private so callers cannot bypass the transition invariants. */
    public static final class State {
        private final ItemType itemType;
        private final RefundScope scope;
        private final AdminDecision decision;
        private final FulfillmentStatus fulfillment;
        private final HoldStatus hold;
        private final CourseAccessStatus courseAccess;
        private final boolean physicalEffectsRequired;
        private final FinancialStatus financial;
        private final boolean settlementCommitted;
        private final long requestedAmountVnd;
        private final boolean courseEligibilityConfirmed;
        private final Set<ReviewReason> reviewReasons;
        private final Map<EventKey, String> appliedEvents;

        private State(ItemType itemType, RefundScope scope, AdminDecision decision, FulfillmentStatus fulfillment,
                      HoldStatus hold, CourseAccessStatus courseAccess, boolean physicalEffectsRequired,
                      FinancialStatus financial, boolean settlementCommitted, long requestedAmountVnd,
                      Set<ReviewReason> reviewReasons, Map<EventKey, String> appliedEvents) {
            this.itemType = Objects.requireNonNull(itemType);
            this.scope = Objects.requireNonNull(scope);
            this.decision = Objects.requireNonNull(decision);
            this.fulfillment = Objects.requireNonNull(fulfillment);
            this.hold = Objects.requireNonNull(hold);
            this.courseAccess = Objects.requireNonNull(courseAccess);
            this.physicalEffectsRequired = physicalEffectsRequired;
            this.financial = Objects.requireNonNull(financial);
            this.settlementCommitted = settlementCommitted;
            this.requestedAmountVnd = requestedAmountVnd;
            this.courseEligibilityConfirmed = courseAccess == CourseAccessStatus.ELIGIBILITY_CONFIRMED
                    || courseAccess == CourseAccessStatus.REVOCATION_PENDING
                    || courseAccess == CourseAccessStatus.REVOKED;
            this.reviewReasons = Set.copyOf(Objects.requireNonNull(reviewReasons));
            this.appliedEvents = Map.copyOf(Objects.requireNonNull(appliedEvents));
            if (!settlementCommitted) throw new IllegalArgumentException("Refund workflow requires a committed settlement");
            if (requestedAmountVnd < 0) throw new IllegalArgumentException("Refund amount cannot be negative");
            boolean courseEffectsRequired = itemType == ItemType.COURSE || itemType == ItemType.COMBO;
            if (!physicalEffectsRequired
                    && (fulfillment != FulfillmentStatus.NOT_REQUIRED || hold != HoldStatus.NOT_APPLICABLE))
                throw new IllegalArgumentException("Nonphysical refund cannot carry Shipment fulfillment or HOLD state");
            if (physicalEffectsRequired
                    && (fulfillment == FulfillmentStatus.NOT_REQUIRED || hold == HoldStatus.NOT_APPLICABLE))
                throw new IllegalArgumentException("Physical refund requires independent fulfillment and HOLD states");
            if (itemType == ItemType.PRODUCT && !physicalEffectsRequired)
                throw new IllegalArgumentException("PRODUCT refund requires physical effects");
            if (courseEffectsRequired && courseAccess == CourseAccessStatus.NOT_APPLICABLE)
                throw new IllegalArgumentException("Course-bearing refund requires a Course-access state");
            if (!courseEffectsRequired && courseAccess != CourseAccessStatus.NOT_APPLICABLE)
                throw new IllegalArgumentException("PRODUCT refund cannot carry a Course-access state");
            if (decision == AdminDecision.REQUESTED && (financial != FinancialStatus.NOT_STARTED
                    || (physicalEffectsRequired && (fulfillment != FulfillmentStatus.PENDING || hold != HoldStatus.ACTIVE))
                    || (courseEffectsRequired && courseAccess != CourseAccessStatus.UNCHANGED))) {
                throw new IllegalArgumentException("Requested workflow requires initial physical and financial states");
            }
            if (decision == AdminDecision.REJECTED && (financial != FinancialStatus.NOT_STARTED
                    || (physicalEffectsRequired && (fulfillment != FulfillmentStatus.RELEASED || hold != HoldStatus.RELEASED))
                    || (courseEffectsRequired && courseAccess != CourseAccessStatus.UNCHANGED))) {
                throw new IllegalArgumentException("Rejected workflow must not retain an active financial or physical request");
            }
            if (decision == AdminDecision.APPROVED
                    && ((physicalEffectsRequired && fulfillment != FulfillmentStatus.ADJUSTED)
                    || (courseEffectsRequired && !courseEligibilityConfirmed))) {
                throw new IllegalArgumentException("Approved workflow requires item-specific evidence");
            }
            if (financial != FinancialStatus.NOT_STARTED && !settlementCommitted) {
                throw new IllegalArgumentException("Financial refund states require a committed settlement");
            }
            if ((financial == FinancialStatus.READY || financial == FinancialStatus.PROCESSING
                    || financial == FinancialStatus.UNKNOWN || financial == FinancialStatus.SUCCEEDED)
                    && (decision != AdminDecision.APPROVED || requestedAmountVnd == 0
                    || (physicalEffectsRequired && fulfillment != FulfillmentStatus.ADJUSTED)
                    || (courseEffectsRequired && !courseEligibilityConfirmed))) {
                throw new IllegalArgumentException("Financial execution requires approved, eligible, positive amount and required fulfillment");
            }
            if (financial == FinancialStatus.FAILED && physicalEffectsRequired
                    && fulfillment == FulfillmentStatus.ADJUSTED
                    && !this.reviewReasons.contains(ReviewReason.PHYSICAL_ADJUSTMENT_WITH_PROVIDER_FAILURE)) {
                throw new IllegalArgumentException("Failed provider refund after physical adjustment requires review");
            }
        }

        public ItemType itemType() { return itemType; }
        public RefundScope scope() { return scope; }
        public AdminDecision decision() { return decision; }
        public FulfillmentStatus fulfillment() { return fulfillment; }
        public HoldStatus hold() { return hold; }
        public CourseAccessStatus courseAccess() { return courseAccess; }
        public boolean physicalEffectsRequired() { return physicalEffectsRequired; }
        public boolean courseAccessEffectsRequired() {
            return itemType == ItemType.COURSE || itemType == ItemType.COMBO;
        }
        public FinancialStatus financial() { return financial; }
        public boolean settlementCommitted() { return settlementCommitted; }
        public long requestedAmountVnd() { return requestedAmountVnd; }
        public boolean courseEligibilityConfirmed() { return courseEligibilityConfirmed; }
        public boolean reviewRequired() { return !reviewReasons.isEmpty(); }
        public Set<ReviewReason> reviewReasons() { return reviewReasons; }
        public boolean effectsComplete() {
            return financial == FinancialStatus.SUCCEEDED
                    && (!courseAccessEffectsRequired() || courseAccess == CourseAccessStatus.REVOKED)
                    && (!physicalEffectsRequired
                    || (fulfillment == FulfillmentStatus.ADJUSTED && hold == HoldStatus.RELEASED));
        }
        /** In-memory replay aid only; persistence is required for restart-safe replay. */
        public int appliedEventCount() { return appliedEvents.size(); }
    }

    public record CreationResult(Optional<State> state, Outcome outcome, Reason reason) {
        public CreationResult {
            Objects.requireNonNull(state); Objects.requireNonNull(outcome); Objects.requireNonNull(reason);
            if (outcome == Outcome.APPLIED && state.isEmpty()) throw new IllegalArgumentException("Applied creation requires state");
            if (outcome != Outcome.APPLIED && state.isPresent()) throw new IllegalArgumentException("Rejected creation has no state");
        }
    }

    public record TransitionResult(State state, Outcome outcome, Reason reason) {
        public TransitionResult { Objects.requireNonNull(state); Objects.requireNonNull(outcome); Objects.requireNonNull(reason); }
    }

    public static CreationResult requestProduct(RefundScope scope, boolean settlementCommitted, long amountVnd,
                                                HoldAcquisitionEvidence holdEvidence) {
        Objects.requireNonNull(scope, "scope");
        if (!settlementCommitted) return rejectedCreation(Reason.UNSETTLED_ORDER);
        if (amountVnd < 0) return rejectedCreation(Reason.INVALID_AMOUNT);
        if (holdEvidence == null || holdEvidence.result() != Evidence.CONFIRMED)
            return rejectedCreation(Reason.HOLD_NOT_CONFIRMED);
        return new CreationResult(Optional.of(new State(ItemType.PRODUCT, scope, AdminDecision.REQUESTED,
                FulfillmentStatus.PENDING, HoldStatus.ACTIVE, CourseAccessStatus.NOT_APPLICABLE, true,
                FinancialStatus.NOT_STARTED, true, amountVnd, Set.of(), Map.of())),
                Outcome.APPLIED, Reason.NONE);
    }

    public static CreationResult requestCourse(RefundScope scope, boolean settlementCommitted, long amountVnd) {
        Objects.requireNonNull(scope, "scope");
        if (!settlementCommitted) return rejectedCreation(Reason.UNSETTLED_ORDER);
        if (amountVnd < 0) return rejectedCreation(Reason.INVALID_AMOUNT);
        return new CreationResult(Optional.of(new State(ItemType.COURSE, scope, AdminDecision.REQUESTED,
                FulfillmentStatus.NOT_REQUIRED, HoldStatus.NOT_APPLICABLE, CourseAccessStatus.UNCHANGED, false,
                FinancialStatus.NOT_STARTED, true, amountVnd, Set.of(), Map.of())),
                Outcome.APPLIED, Reason.NONE);
    }

    public static CreationResult requestCourseWithPhysicalGifts(RefundScope scope, boolean settlementCommitted,
                                                                 long amountVnd,
                                                                 HoldAcquisitionEvidence holdEvidence) {
        return requestCourseBearing(ItemType.COURSE, scope, settlementCommitted, amountVnd, true, holdEvidence);
    }

    /** A Combo is one whole financial obligation; component-level scopes are deliberately not represented. */
    public static CreationResult requestCombo(RefundScope scope, boolean settlementCommitted, long amountVnd,
                                              boolean hasPhysicalGifts,
                                              HoldAcquisitionEvidence holdEvidence) {
        if (!hasPhysicalGifts && holdEvidence != null)
            throw new IllegalArgumentException("Nonphysical Combo cannot carry HOLD evidence");
        return requestCourseBearing(ItemType.COMBO, scope, settlementCommitted, amountVnd,
                hasPhysicalGifts, holdEvidence);
    }

    private static CreationResult requestCourseBearing(ItemType itemType, RefundScope scope,
                                                        boolean settlementCommitted, long amountVnd,
                                                        boolean physical,
                                                        HoldAcquisitionEvidence holdEvidence) {
        Objects.requireNonNull(scope, "scope");
        if (itemType != ItemType.COURSE && itemType != ItemType.COMBO)
            throw new IllegalArgumentException("Course-bearing refund type required");
        if (!settlementCommitted) return rejectedCreation(Reason.UNSETTLED_ORDER);
        if (amountVnd < 0) return rejectedCreation(Reason.INVALID_AMOUNT);
        if (physical && (holdEvidence == null || holdEvidence.result() != Evidence.CONFIRMED))
            return rejectedCreation(Reason.HOLD_NOT_CONFIRMED);
        if (!physical && holdEvidence != null)
            throw new IllegalArgumentException("Nonphysical refund cannot carry HOLD evidence");
        return new CreationResult(Optional.of(new State(itemType, scope, AdminDecision.REQUESTED,
                physical ? FulfillmentStatus.PENDING : FulfillmentStatus.NOT_REQUIRED,
                physical ? HoldStatus.ACTIVE : HoldStatus.NOT_APPLICABLE,
                CourseAccessStatus.UNCHANGED, physical, FinancialStatus.NOT_STARTED,
                true, amountVnd, Set.of(), Map.of())), Outcome.APPLIED, Reason.NONE);
    }

    /** Admin approval is all-or-reject. Product adjustment evidence must already be authoritative. */
    public static TransitionResult approve(State state, EventIdentity event, ApprovalEvidence approvalEvidence) {
        var invalid = validateEvent(state, event, OperationType.ADMIN_APPROVAL);
        if (invalid != null) return invalid;
        var replay = replay(state, event);
        if (replay != null) return replay;
        if (state.decision() == AdminDecision.APPROVED) return applyEvent(state, event, Outcome.ALREADY_APPLIED, Reason.NONE, state);
        if (state.decision() == AdminDecision.REJECTED) return reject(state, Reason.DECISION_ALREADY_REJECTED);
        boolean physicalConfirmed = !state.physicalEffectsRequired();
        boolean courseConfirmed = !state.courseAccessEffectsRequired();
        if (state.itemType() == ItemType.PRODUCT
                && approvalEvidence instanceof ProductApprovalEvidence productEvidence) {
            physicalConfirmed = productEvidence.fulfillmentAdjustment() == Evidence.CONFIRMED;
        } else if (state.courseAccessEffectsRequired() && !state.physicalEffectsRequired()
                && approvalEvidence instanceof CourseApprovalEvidence courseEvidence) {
            courseConfirmed = courseEvidence.eligibility() == Evidence.CONFIRMED;
        } else if (state.courseAccessEffectsRequired() && state.physicalEffectsRequired()
                && approvalEvidence instanceof CourseWithPhysicalGiftApprovalEvidence combined) {
            courseConfirmed = combined.eligibility() == Evidence.CONFIRMED;
            physicalConfirmed = combined.fulfillmentAdjustment() == Evidence.CONFIRMED;
        }
        if (state.physicalEffectsRequired()) {
            if (state.hold() != HoldStatus.ACTIVE) return reject(state, Reason.HOLD_NOT_CONFIRMED);
            if (!physicalConfirmed)
                return reject(state, Reason.FULFILLMENT_ADJUSTMENT_NOT_CONFIRMED);
        }
        if (state.courseAccessEffectsRequired() && !courseConfirmed) {
            return reject(state, Reason.COURSE_ELIGIBILITY_NOT_CONFIRMED);
        }
        var financial = state.requestedAmountVnd() == 0 ? FinancialStatus.NOT_STARTED : FinancialStatus.READY;
        var next = copyWithCourseAccess(state, AdminDecision.APPROVED,
                state.physicalEffectsRequired() ? FulfillmentStatus.ADJUSTED : FulfillmentStatus.NOT_REQUIRED,
                state.hold(), financial,
                state.courseAccessEffectsRequired()
                        ? CourseAccessStatus.ELIGIBILITY_CONFIRMED : CourseAccessStatus.NOT_APPLICABLE,
                Set.of(), state.appliedEvents);
        return applyEvent(state, event, Outcome.APPLIED, Reason.NONE, next);
    }

    /** Product rejection is accepted only with authoritative release confirmation for this request's HOLD. */
    public static TransitionResult reject(State state, EventIdentity event, HoldReleaseEvidence holdEvidence) {
        var invalid = validateEvent(state, event, OperationType.ADMIN_REJECTION);
        if (invalid != null) return invalid;
        var replay = replay(state, event);
        if (replay != null) return replay;
        if (state.decision() == AdminDecision.REJECTED) return applyEvent(state, event, Outcome.ALREADY_APPLIED, Reason.NONE, state);
        if (state.decision() == AdminDecision.APPROVED) return reject(state, Reason.DECISION_ALREADY_APPROVED);
        if (state.physicalEffectsRequired()
                && (holdEvidence == null || holdEvidence.result() != Evidence.CONFIRMED)) {
            return reject(state, Reason.HOLD_RELEASE_NOT_CONFIRMED);
        }
        var next = copy(state, AdminDecision.REJECTED,
                state.physicalEffectsRequired() ? FulfillmentStatus.RELEASED : FulfillmentStatus.NOT_REQUIRED,
                state.physicalEffectsRequired() ? HoldStatus.RELEASED : HoldStatus.NOT_APPLICABLE,
                FinancialStatus.NOT_STARTED, false, Set.of(), state.appliedEvents);
        return applyEvent(state, event, Outcome.APPLIED, Reason.NONE, next);
    }

    /** Releases only the Shipment HOLD after an approved physical adjustment is durably complete. */
    public static TransitionResult releaseHoldAfterAdjustment(State state, EventIdentity event,
                                                              HoldReleaseEvidence holdEvidence) {
        var invalid = validateEvent(state, event, OperationType.HOLD_RELEASE_AFTER_ADJUSTMENT);
        if (invalid != null) return invalid;
        var replay = replay(state, event);
        if (replay != null) return replay;
        if (!state.physicalEffectsRequired() || state.decision() != AdminDecision.APPROVED
                || state.fulfillment() != FulfillmentStatus.ADJUSTED) {
            return reject(state, Reason.FULFILLMENT_NOT_ADJUSTED);
        }
        if (holdEvidence == null || holdEvidence.result() != Evidence.CONFIRMED)
            return reject(state, Reason.HOLD_RELEASE_NOT_CONFIRMED);
        var next = copy(state, state.decision(), FulfillmentStatus.ADJUSTED, HoldStatus.RELEASED,
                state.financial(), state.courseEligibilityConfirmed(), state.reviewReasons, state.appliedEvents);
        return applyEvent(state, event, Outcome.APPLIED, Reason.NONE, next);
    }

    public static TransitionResult beginFinancialExecution(State state, EventIdentity event) {
        var invalid = validateEvent(state, event, OperationType.FINANCIAL_EXECUTION_START);
        if (invalid != null) return invalid;
        var replay = replay(state, event);
        if (replay != null) return replay;
        if (state.financial() == FinancialStatus.UNKNOWN) return reject(state, Reason.UNKNOWN_REQUIRES_RECONCILIATION);
        if (state.financial() != FinancialStatus.READY) return reject(state, Reason.FINANCIAL_NOT_READY);
        var next = copy(state, state.decision(), state.fulfillment(), state.hold(), FinancialStatus.PROCESSING,
                state.courseEligibilityConfirmed(), state.reviewReasons, state.appliedEvents);
        return applyEvent(state, event, Outcome.APPLIED, Reason.NONE, next);
    }

    /** UNKNOWN is a durable stop; only later verified reconciliation evidence can resolve it. */
    public static TransitionResult markUnknown(State state, EventIdentity event) {
        var invalid = validateEvent(state, event, OperationType.LOCAL_PROVIDER_TIMEOUT);
        if (invalid != null) return invalid;
        var replay = replay(state, event);
        if (replay != null) return replay;
        if (state.financial() != FinancialStatus.PROCESSING) return reject(state, Reason.FINANCIAL_NOT_READY);
        var next = copy(state, state.decision(), state.fulfillment(), state.hold(), FinancialStatus.UNKNOWN,
                state.courseEligibilityConfirmed(), withReviewReason(state, ReviewReason.PROVIDER_OUTCOME_UNKNOWN), state.appliedEvents);
        return applyEvent(state, event, Outcome.APPLIED, Reason.NONE, next);
    }

    public static TransitionResult applyProviderEvidence(State state, ProviderEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        var event = evidence.event();
        var invalid = validateEvent(state, event, OperationType.PROVIDER_OUTCOME);
        if (invalid != null) return invalid;
        var replay = replay(state, event);
        if (replay != null) return replay;
        if (state.financial() != FinancialStatus.PROCESSING && state.financial() != FinancialStatus.UNKNOWN) {
            return reject(state, state.financial() == FinancialStatus.SUCCEEDED
                    ? Reason.SUCCESS_ALREADY_RECORDED : Reason.FINANCIAL_NOT_READY);
        }
        if (evidence.signature() != Evidence.CONFIRMED) return reject(state, Reason.PROVIDER_EVIDENCE_UNVERIFIED);
        if (evidence.amountMatch() != Evidence.CONFIRMED) return reject(state, Reason.PROVIDER_AMOUNT_MISMATCH);
        if (evidence.currencyMatch() != Evidence.CONFIRMED) return reject(state, Reason.PROVIDER_CURRENCY_MISMATCH);
        if (evidence.outcome() == ProviderOutcome.AMBIGUOUS) return reject(state, Reason.PROVIDER_EVENT_AMBIGUOUS);
        if (state.financial() == FinancialStatus.UNKNOWN && evidence.kind() != ProviderEvidenceKind.RECONCILIATION)
            return reject(state, Reason.UNKNOWN_REQUIRES_RECONCILIATION);
        if (state.financial() == FinancialStatus.SUCCEEDED || state.financial() == FinancialStatus.FAILED
                || state.financial() == FinancialStatus.REQUIRES_REVIEW) {
            return reject(state, Reason.PROVIDER_OUTCOME_CONFLICT);
        }
        FinancialStatus financial;
        Set<ReviewReason> reviewReasons = new HashSet<>(state.reviewReasons);
        switch (evidence.outcome()) {
            case SUCCESS -> {
                financial = FinancialStatus.SUCCEEDED;
                reviewReasons.remove(ReviewReason.PROVIDER_OUTCOME_UNKNOWN);
            }
            case DEFINITIVE_FAILURE -> {
                financial = FinancialStatus.FAILED;
                // An adjusted Product fulfillment cannot be safely reopened by a payment failure.
                reviewReasons.remove(ReviewReason.PROVIDER_OUTCOME_UNKNOWN);
                if (state.physicalEffectsRequired() && state.fulfillment() == FulfillmentStatus.ADJUSTED)
                    reviewReasons.add(ReviewReason.PHYSICAL_ADJUSTMENT_WITH_PROVIDER_FAILURE);
            }
            case UNKNOWN -> {
                financial = FinancialStatus.UNKNOWN;
                reviewReasons.add(ReviewReason.PROVIDER_OUTCOME_UNKNOWN);
            }
            case AMBIGUOUS -> throw new IllegalStateException("Handled above");
            default -> throw new IllegalStateException("Unhandled provider outcome");
        }
        CourseAccessStatus courseAccess = state.courseAccess();
        if (evidence.outcome() == ProviderOutcome.SUCCESS && state.courseAccessEffectsRequired())
            courseAccess = CourseAccessStatus.REVOCATION_PENDING;
        var next = copyWithCourseAccess(state, state.decision(), state.fulfillment(), state.hold(), financial,
                courseAccess, reviewReasons, state.appliedEvents);
        return applyEvent(state, event, Outcome.APPLIED, Reason.NONE, next);
    }

    /** Records C's idempotent whole-OrderItem access revocation after verified financial success. */
    public static TransitionResult confirmCourseAccessRevoked(State state, EventIdentity event,
                                                              Evidence revocationEvidence) {
        var invalid = validateEvent(state, event, OperationType.COURSE_ACCESS_REVOCATION);
        if (invalid != null) return invalid;
        var replay = replay(state, event);
        if (replay != null) return replay;
        if (!state.courseAccessEffectsRequired()
                || state.financial() != FinancialStatus.SUCCEEDED
                || state.courseAccess() != CourseAccessStatus.REVOCATION_PENDING
                || revocationEvidence != Evidence.CONFIRMED)
            return reject(state, Reason.COURSE_ACCESS_REVOCATION_NOT_CONFIRMED);
        var next = copyWithCourseAccess(state, state.decision(), state.fulfillment(), state.hold(),
                state.financial(), CourseAccessStatus.REVOKED, state.reviewReasons(), state.appliedEvents);
        return applyEvent(state, event, Outcome.APPLIED, Reason.NONE, next);
    }

    private static CreationResult rejectedCreation(Reason reason) {
        return new CreationResult(Optional.empty(), Outcome.REJECTED, reason);
    }
    private static TransitionResult reject(State state, Reason reason) {
        return new TransitionResult(state, Outcome.REJECTED, reason);
    }
    private static TransitionResult replay(State state, EventIdentity event) {
        Objects.requireNonNull(event, "event");
        if (!state.scope().equals(event.scope())) return reject(state, Reason.INVALID_EVENT_IDENTITY);
        var old = state.appliedEvents.get(event.key());
        if (old == null) return null;
        return old.equals(event.payloadSha256())
                ? new TransitionResult(state, Outcome.ALREADY_APPLIED, Reason.NONE)
                : reject(state, Reason.EVENT_IDENTITY_CONFLICT);
    }
    private static TransitionResult applyEvent(State original, EventIdentity event, Outcome outcome,
                                               Reason reason, State next) {
        var events = new HashMap<>(next.appliedEvents);
        events.put(event.key(), event.payloadSha256());
        var withEvent = copy(next, next.decision(), next.fulfillment(), next.hold(), next.financial(),
                next.courseEligibilityConfirmed(), next.reviewReasons, events);
        return new TransitionResult(withEvent, outcome, reason);
    }
    private static TransitionResult validateEvent(State state, EventIdentity event, OperationType expected) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");
        if (event.operationType() != expected || !state.scope().equals(event.scope()))
            return reject(state, Reason.INVALID_EVENT_IDENTITY);
        return null;
    }
    private static Set<ReviewReason> withReviewReason(State state, ReviewReason reason) {
        var reasons = new HashSet<>(state.reviewReasons);
        reasons.add(reason);
        return Set.copyOf(reasons);
    }
    private static State copy(State source, AdminDecision decision, FulfillmentStatus fulfillment,
                              HoldStatus hold, FinancialStatus financial, boolean courseEligible,
                              Set<ReviewReason> reviewReasons, Map<EventKey, String> events) {
        CourseAccessStatus access = source.courseAccessEffectsRequired()
                ? (courseEligible ? source.courseAccess() : CourseAccessStatus.UNCHANGED)
                : CourseAccessStatus.NOT_APPLICABLE;
        return copyWithCourseAccess(source, decision, fulfillment, hold, financial, access, reviewReasons, events);
    }

    private static State copyWithCourseAccess(State source, AdminDecision decision,
                                              FulfillmentStatus fulfillment, HoldStatus hold,
                                              FinancialStatus financial, CourseAccessStatus courseAccess,
                                              Set<ReviewReason> reviewReasons, Map<EventKey, String> events) {
        return new State(source.itemType(), source.scope(), decision, fulfillment, hold, courseAccess,
                source.physicalEffectsRequired(), financial, source.settlementCommitted(),
                source.requestedAmountVnd(), reviewReasons, events);
    }
}
