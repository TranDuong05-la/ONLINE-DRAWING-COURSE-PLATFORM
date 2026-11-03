package com.drawingcourse.backend.service.settlement;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicitly invoked, bounded orchestration only. This class is deliberately not a Spring bean,
 * scheduler, thread owner, application runner, or HTTP boundary.
 */
public final class SettlementWorkerOrchestrator {
    /** Safety ceiling for one explicit invocation; this is not a production polling configuration. */
    public static final int MAX_BATCH_SIZE = 64;

    private final ClaimOperations claims;
    private final ClaimedSettlementExecutor settlements;

    /** Production-shaped adapter constructor; constructing this class does not register or execute it. */
    public SettlementWorkerOrchestrator(SettlementWorkClaimService claimService,
                                        SettlementCoordinator settlementCoordinator) {
        this(adapt(Objects.requireNonNull(claimService, "claimService")),
                Objects.requireNonNull(settlementCoordinator, "settlementCoordinator")::executeClaimedWork);
    }

    /** Pure orchestration boundary used by isolated tests and explicit future wiring. */
    public SettlementWorkerOrchestrator(ClaimOperations claims, ClaimedSettlementExecutor settlements) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.settlements = Objects.requireNonNull(settlements, "settlements");
    }

    /**
     * Claims and processes at most {@code limit} items sequentially. Every claim operation has returned
     * (and therefore committed in the production adapter) before its settlement execution begins.
     */
    public BatchSummary runBatch(String workerId, int limit) {
        requireNoAmbientTransaction();
        validateWorkerId(workerId);
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }

        List<ItemResult> items = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            Optional<SettlementWorkClaimService.Claim> next = claims.claimNext(workerId);
            if (next.isEmpty()) break;
            items.add(process(next.orElseThrow()));
        }
        return BatchSummary.from(limit, items);
    }

    private ItemResult process(SettlementWorkClaimService.Claim claim) {
        final SettlementResult result;
        try {
            result = settlements.execute(claim);
        } catch (SettlementOutcomeUnknownException unknown) {
            return ItemResult.unknown(claim, Observation.COMMIT_OUTCOME_UNKNOWN);
        } catch (RuntimeException unexpected) {
            // No state transition is safe from an in-memory exception alone. Leave the fenced lease for
            // fresh persisted inspection or expiry/reclaim and continue with other claimed items.
            return ItemResult.unknown(claim, Observation.EXECUTION_EXCEPTION);
        }

        if (result == null || !matchesClaim(result, claim)) {
            return ItemResult.unknown(claim, Observation.RESULT_EVIDENCE_MISMATCH);
        }

        return switch (result.outcome()) {
            case SETTLED, ALREADY_SETTLED -> completedResult(claim, result);
            case RETRYABLE_FAILURE -> retryResult(claim, result);
            case REQUIRES_REVIEW, NOT_ELIGIBLE -> reviewResult(claim, result);
        };
    }

    private ItemResult completedResult(SettlementWorkClaimService.Claim claim, SettlementResult result) {
        if (result.reason() != SettlementResult.Reason.NONE
                || result.orderId() == null || result.orderId() <= 0
                || result.settlementId() == null || result.settlementId() <= 0) {
            return ItemResult.unknown(claim, result, Observation.RESULT_EVIDENCE_MISMATCH);
        }
        Observation observation = result.outcome() == SettlementResult.Outcome.SETTLED
                ? Observation.SETTLED : Observation.ALREADY_SETTLED;
        return new ItemResult(claim.id(), claim.paymentEventId(), claim.attemptCount(),
                Disposition.COMPLETED, result.outcome(), result.reason(), observation);
    }

    private ItemResult retryResult(SettlementWorkClaimService.Claim claim, SettlementResult result) {
        if (result.reason() != SettlementResult.Reason.TRANSIENT_DATABASE_FAILURE) {
            return ItemResult.unknown(claim, result, Observation.RESULT_EVIDENCE_MISMATCH);
        }
        final SettlementWorkClaimService.RetryResult retry;
        try {
            retry = claims.recordTransientFailure(claim,
                    SettlementWorkClaimService.TransientFailure.TRANSIENT_DATABASE);
        } catch (RuntimeException uncertainRetryTransition) {
            return ItemResult.unknown(claim, result, Observation.RETRY_TRANSITION_UNKNOWN);
        }
        return switch (retry) {
            case RETRY_SCHEDULED -> new ItemResult(claim.id(), claim.paymentEventId(), claim.attemptCount(),
                    Disposition.RETRY_WAIT, result.outcome(), result.reason(), Observation.RETRY_SCHEDULED);
            case MANUAL_REVIEW -> new ItemResult(claim.id(), claim.paymentEventId(), claim.attemptCount(),
                    Disposition.MANUAL_REVIEW, result.outcome(), result.reason(), Observation.ATTEMPTS_EXHAUSTED);
            case STALE_LEASE -> ItemResult.unknown(claim, result, Observation.STALE_LEASE);
        };
    }

    private ItemResult reviewResult(SettlementWorkClaimService.Claim claim, SettlementResult result) {
        if (result.reason() == SettlementResult.Reason.COMMIT_OUTCOME_UNKNOWN) {
            return ItemResult.unknown(claim, result, Observation.COMMIT_OUTCOME_UNKNOWN);
        }
        if (result.reason() == SettlementResult.Reason.STALE_WORK_CLAIM) {
            return ItemResult.unknown(claim, result, Observation.STALE_LEASE);
        }
        if (result.reason() == SettlementResult.Reason.TRANSIENT_DATABASE_FAILURE
                || result.reason() == SettlementResult.Reason.NONE) {
            return ItemResult.unknown(claim, result, Observation.RESULT_EVIDENCE_MISMATCH);
        }
        // executeClaimedWork returns these outcomes only after its token-fenced MANUAL_REVIEW write commits.
        // Automatic workers never perform the audited operator-only CLOSED_NOT_ELIGIBLE transition.
        return new ItemResult(claim.id(), claim.paymentEventId(), claim.attemptCount(),
                Disposition.MANUAL_REVIEW, result.outcome(), result.reason(), Observation.PERSISTED_MANUAL_REVIEW);
    }

    private static boolean matchesClaim(SettlementResult result, SettlementWorkClaimService.Claim claim) {
        return result.paymentEventId() != null && result.paymentEventId() == claim.paymentEventId();
    }

    private static ClaimOperations adapt(SettlementWorkClaimService service) {
        return new ClaimOperations() {
            @Override
            public Optional<SettlementWorkClaimService.Claim> claimNext(String workerId) {
                return service.claimNext(workerId);
            }

            @Override
            public SettlementWorkClaimService.RetryResult recordTransientFailure(
                    SettlementWorkClaimService.Claim claim,
                    SettlementWorkClaimService.TransientFailure failure) {
                return service.recordTransientFailure(claim, failure);
            }
        };
    }

    private static void validateWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 64
                || !workerId.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("workerId must be 1..64 ASCII identifier characters");
        }
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Settlement worker orchestration rejects an ambient transaction");
        }
    }

    public interface ClaimOperations {
        Optional<SettlementWorkClaimService.Claim> claimNext(String workerId);

        SettlementWorkClaimService.RetryResult recordTransientFailure(
                SettlementWorkClaimService.Claim claim,
                SettlementWorkClaimService.TransientFailure failure);
    }

    @FunctionalInterface
    public interface ClaimedSettlementExecutor {
        SettlementResult execute(SettlementWorkClaimService.Claim claim);
    }

    public enum Disposition { COMPLETED, RETRY_WAIT, MANUAL_REVIEW, CLOSED_NOT_ELIGIBLE, UNKNOWN }

    public enum Observation {
        SETTLED,
        ALREADY_SETTLED,
        RETRY_SCHEDULED,
        ATTEMPTS_EXHAUSTED,
        PERSISTED_MANUAL_REVIEW,
        STALE_LEASE,
        COMMIT_OUTCOME_UNKNOWN,
        RESULT_EVIDENCE_MISMATCH,
        EXECUTION_EXCEPTION,
        RETRY_TRANSITION_UNKNOWN
    }

    public record ItemResult(int workItemId, int paymentEventId, int attemptCount,
                             Disposition disposition, SettlementResult.Outcome settlementOutcome,
                             SettlementResult.Reason settlementReason, Observation observation) {
        public ItemResult {
            if (workItemId <= 0 || paymentEventId <= 0 || attemptCount < 1) {
                throw new IllegalArgumentException("Invalid claimed work identity");
            }
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(observation, "observation");
        }

        private static ItemResult unknown(SettlementWorkClaimService.Claim claim, Observation observation) {
            return new ItemResult(claim.id(), claim.paymentEventId(), claim.attemptCount(),
                    Disposition.UNKNOWN, null, null, observation);
        }

        private static ItemResult unknown(SettlementWorkClaimService.Claim claim, SettlementResult result,
                                          Observation observation) {
            return new ItemResult(claim.id(), claim.paymentEventId(), claim.attemptCount(),
                    Disposition.UNKNOWN, result.outcome(), result.reason(), observation);
        }
    }

    public record BatchSummary(int requestedLimit, int claimed, int completed, int retryWait,
                               int manualReview, int closedNotEligible, int unknown,
                               List<ItemResult> items) {
        public BatchSummary {
            items = List.copyOf(items);
            if (requestedLimit < 1 || requestedLimit > MAX_BATCH_SIZE || claimed < 0
                    || completed < 0 || retryWait < 0 || manualReview < 0
                    || closedNotEligible < 0 || unknown < 0
                    || claimed != completed + retryWait + manualReview + closedNotEligible + unknown
                    || claimed != items.size()) {
                throw new IllegalArgumentException("Inconsistent settlement worker batch summary");
            }
        }

        private static BatchSummary from(int requestedLimit, List<ItemResult> items) {
            int completed = 0;
            int retryWait = 0;
            int manualReview = 0;
            int closedNotEligible = 0;
            int unknown = 0;
            for (ItemResult item : items) {
                switch (item.disposition()) {
                    case COMPLETED -> completed++;
                    case RETRY_WAIT -> retryWait++;
                    case MANUAL_REVIEW -> manualReview++;
                    case CLOSED_NOT_ELIGIBLE -> closedNotEligible++;
                    case UNKNOWN -> unknown++;
                }
            }
            return new BatchSummary(requestedLimit, items.size(), completed, retryWait,
                    manualReview, closedNotEligible, unknown, items);
        }
    }
}
