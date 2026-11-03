package com.drawingcourse.backend.service.settlement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.AnnotatedElement;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.drawingcourse.backend.service.settlement.SettlementWorkerOrchestrator.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementWorkerOrchestratorTest {

    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void emptyEligibleQueueReturnsZeroSummary() {
        var worker = worker(new FakeClaims(), claim -> fail("No claim should execute"));

        BatchSummary result = worker.runBatch("worker-a", 4);

        assertEquals(0, result.claimed());
        assertEquals(0, result.completed());
        assertEquals(0, result.retryWait());
        assertEquals(0, result.manualReview());
        assertEquals(0, result.closedNotEligible());
        assertEquals(0, result.unknown());
        assertTrue(result.items().isEmpty());
    }

    @Test
    void singleClaimCommitsBeforeSuccessfulSettlementExecution() {
        FakeClaims claims = new FakeClaims(claim(1, 101, 1));
        AtomicInteger sequence = new AtomicInteger();
        claims.onClaim = ignored -> sequence.incrementAndGet();
        var worker = worker(claims, claim -> {
            assertEquals(1, sequence.get(), "claim operation must return before settlement starts");
            sequence.incrementAndGet();
            return settled(claim);
        });

        BatchSummary result = worker.runBatch("worker-a", 1);

        assertEquals(2, sequence.get());
        assertEquals(1, result.claimed());
        assertEquals(1, result.completed());
        assertEquals(Observation.SETTLED, result.items().getFirst().observation());
    }

    @Test
    void multipleItemsAreProcessedIndependentlyAndInClaimOrder() {
        FakeClaims claims = new FakeClaims(claim(1, 101, 1), claim(2, 102, 1), claim(3, 103, 1));
        var worker = worker(claims, claim -> switch (claim.id()) {
            case 1, 3 -> settled(claim);
            case 2 -> review(claim, SettlementResult.Reason.SETTLEMENT_CONFLICT);
            default -> throw new AssertionError();
        });

        BatchSummary result = worker.runBatch("worker-a", 3);

        assertEquals(3, result.claimed());
        assertEquals(2, result.completed());
        assertEquals(1, result.manualReview());
        assertEquals(java.util.List.of(1, 2, 3), result.items().stream().map(ItemResult::workItemId).toList());
    }

    @Test
    void retryableFailureUsesFencedRetryTransition() {
        var claim = claim(1, 101, 2);
        FakeClaims claims = new FakeClaims(claim);
        claims.retryResults.put(claim.id(), SettlementWorkClaimService.RetryResult.RETRY_SCHEDULED);
        var worker = worker(claims, SettlementWorkerOrchestratorTest::retryable);

        BatchSummary result = worker.runBatch("worker-a", 1);

        assertEquals(1, claims.retryCalls.get());
        assertEquals(SettlementWorkClaimService.TransientFailure.TRANSIENT_DATABASE, claims.lastFailure);
        assertEquals(1, result.retryWait());
        assertEquals(Observation.RETRY_SCHEDULED, result.items().getFirst().observation());
    }

    @Test
    void terminalBusinessConflictIsCountedAsPersistedManualReview() {
        var worker = worker(new FakeClaims(claim(1, 101, 1)),
                claim -> review(claim, SettlementResult.Reason.ORDER_NOT_ELIGIBLE));

        BatchSummary result = worker.runBatch("worker-a", 1);

        assertEquals(1, result.manualReview());
        assertEquals(0, result.closedNotEligible());
        assertEquals(Observation.PERSISTED_MANUAL_REVIEW, result.items().getFirst().observation());
    }

    @Test
    void staleOrExpiredLeaseCannotBeChangedByRetryOwner() {
        var claim = claim(1, 101, 2);
        FakeClaims claims = new FakeClaims(claim);
        claims.retryResults.put(claim.id(), SettlementWorkClaimService.RetryResult.STALE_LEASE);
        var worker = worker(claims, SettlementWorkerOrchestratorTest::retryable);

        BatchSummary result = worker.runBatch("worker-a", 1);

        assertEquals(1, result.unknown());
        assertEquals(Observation.STALE_LEASE, result.items().getFirst().observation());
    }

    @Test
    void duplicateWorkerInvocationDoesNotReexecuteConsumedClaim() {
        FakeClaims claims = new FakeClaims(claim(1, 101, 1));
        AtomicInteger executions = new AtomicInteger();
        var worker = worker(claims, claim -> {
            executions.incrementAndGet();
            return settled(claim);
        });

        BatchSummary first = worker.runBatch("worker-a", 1);
        BatchSummary duplicate = worker.runBatch("worker-a", 1);

        assertEquals(1, first.completed());
        assertEquals(0, duplicate.claimed());
        assertEquals(1, executions.get());
    }

    @Test
    void concurrentClaimAttemptsShareOnlyOneCommittedClaim() throws Exception {
        FakeClaims shared = new FakeClaims(claim(1, 101, 1));
        AtomicInteger executions = new AtomicInteger();
        var first = worker(shared, claim -> { executions.incrementAndGet(); return settled(claim); });
        var second = worker(shared, claim -> { executions.incrementAndGet(); return settled(claim); });
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return first.runBatch("worker-a", 1); });
            var b = pool.submit(() -> { start.await(); return second.runBatch("worker-b", 1); });
            start.countDown();
            int totalClaimed = a.get(5, TimeUnit.SECONDS).claimed() + b.get(5, TimeUnit.SECONDS).claimed();
            assertEquals(1, totalClaimed);
        }
        assertEquals(1, executions.get());
    }

    @Test
    void eighthAttemptExhaustionMovesToManualReviewWithoutNinthClaim() {
        var claim = claim(1, 101, 8);
        FakeClaims claims = new FakeClaims(claim);
        claims.retryResults.put(claim.id(), SettlementWorkClaimService.RetryResult.MANUAL_REVIEW);
        var worker = worker(claims, SettlementWorkerOrchestratorTest::retryable);

        BatchSummary result = worker.runBatch("worker-a", 1);

        assertEquals(1, result.manualReview());
        assertEquals(Observation.ATTEMPTS_EXHAUSTED, result.items().getFirst().observation());
        assertEquals(1, claims.retryCalls.get());
    }

    @Test
    void ambiguousCommitResolvedByPersistedSettlementIsCompletedReplay() {
        var worker = worker(new FakeClaims(claim(1, 101, 1)), SettlementWorkerOrchestratorTest::alreadySettled);

        BatchSummary result = worker.runBatch("worker-a", 1);

        assertEquals(1, result.completed());
        assertEquals(Observation.ALREADY_SETTLED, result.items().getFirst().observation());
    }

    @Test
    void unresolvedAmbiguousCommitRemainsUnknownWithoutRetryMutation() {
        FakeClaims claims = new FakeClaims(claim(1, 101, 1));
        var worker = worker(claims, claim -> {
            throw new SettlementOutcomeUnknownException("unresolved", new IllegalStateException("fresh read failed"));
        });

        BatchSummary result = worker.runBatch("worker-a", 1);

        assertEquals(1, result.unknown());
        assertEquals(0, claims.retryCalls.get());
        assertEquals(Observation.COMMIT_OUTCOME_UNKNOWN, result.items().getFirst().observation());
    }

    @Test
    void batchFailureIsolationContinuesAfterUnexpectedItemFailure() {
        FakeClaims claims = new FakeClaims(claim(1, 101, 1), claim(2, 102, 1), claim(3, 103, 1));
        AtomicInteger executions = new AtomicInteger();
        var worker = worker(claims, claim -> {
            executions.incrementAndGet();
            if (claim.id() == 2) throw new IllegalStateException("participant failed");
            return settled(claim);
        });

        BatchSummary result = worker.runBatch("worker-a", 3);

        assertEquals(3, executions.get());
        assertEquals(2, result.completed());
        assertEquals(1, result.unknown());
        assertEquals(Observation.EXECUTION_EXCEPTION, result.items().get(1).observation());
    }

    @Test
    void ambientTransactionIsRejectedBeforeClaiming() {
        FakeClaims claims = new FakeClaims(claim(1, 101, 1));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class, () -> worker(claims, SettlementWorkerOrchestratorTest::settled)
                .runBatch("worker-a", 1));
        assertEquals(0, claims.claimCalls.get());
    }

    @Test
    void classHasNoAccidentalProductionActivationAnnotations() {
        Class<SettlementWorkerOrchestrator> type = SettlementWorkerOrchestrator.class;
        assertFalse(type.isAnnotationPresent(Component.class));
        assertFalse(type.isAnnotationPresent(Service.class));
        assertFalse(type.isAnnotationPresent(org.springframework.context.annotation.Configuration.class));
        assertFalse(type.isAnnotationPresent(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class));
        assertTrue(java.util.Arrays.stream(type.getDeclaredMethods())
                .noneMatch(method -> ((AnnotatedElement) method).isAnnotationPresent(Scheduled.class)));
    }

    @Test
    void invalidOrMismatchedCompletionEvidenceRemainsUnknown() {
        var claim = claim(1, 101, 1);
        var worker = worker(new FakeClaims(claim), ignored -> new SettlementResult(
                SettlementResult.Outcome.SETTLED, 50, 999, 70, SettlementResult.Reason.NONE));

        BatchSummary result = worker.runBatch("worker-a", 1);

        assertEquals(1, result.unknown());
        assertEquals(Observation.RESULT_EVIDENCE_MISMATCH, result.items().getFirst().observation());
    }

    private static SettlementWorkerOrchestrator worker(FakeClaims claims, ClaimedSettlementExecutor executor) {
        return new SettlementWorkerOrchestrator(claims, executor);
    }

    private static SettlementWorkClaimService.Claim claim(int id, int eventId, int attempt) {
        Instant now = Instant.parse("2026-10-30T00:00:00Z");
        return new SettlementWorkClaimService.Claim(id, eventId, "token-" + id, "worker", attempt,
                0, now, now.plusSeconds(120));
    }

    private static SettlementResult settled(SettlementWorkClaimService.Claim claim) {
        return new SettlementResult(SettlementResult.Outcome.SETTLED, 50 + claim.id(), claim.paymentEventId(),
                70 + claim.id(), SettlementResult.Reason.NONE);
    }

    private static SettlementResult alreadySettled(SettlementWorkClaimService.Claim claim) {
        return new SettlementResult(SettlementResult.Outcome.ALREADY_SETTLED, 50 + claim.id(),
                claim.paymentEventId(), 70 + claim.id(), SettlementResult.Reason.NONE);
    }

    private static SettlementResult retryable(SettlementWorkClaimService.Claim claim) {
        return new SettlementResult(SettlementResult.Outcome.RETRYABLE_FAILURE, 50 + claim.id(),
                claim.paymentEventId(), null, SettlementResult.Reason.TRANSIENT_DATABASE_FAILURE);
    }

    private static SettlementResult review(SettlementWorkClaimService.Claim claim, SettlementResult.Reason reason) {
        return new SettlementResult(SettlementResult.Outcome.REQUIRES_REVIEW, 50 + claim.id(),
                claim.paymentEventId(), null, reason);
    }

    private static final class FakeClaims implements ClaimOperations {
        private final Queue<SettlementWorkClaimService.Claim> queue = new ConcurrentLinkedQueue<>();
        private final Map<Integer, SettlementWorkClaimService.RetryResult> retryResults = new ConcurrentHashMap<>();
        private final AtomicInteger claimCalls = new AtomicInteger();
        private final AtomicInteger retryCalls = new AtomicInteger();
        private volatile SettlementWorkClaimService.TransientFailure lastFailure;
        private volatile Function<SettlementWorkClaimService.Claim, Integer> onClaim = ignored -> 0;

        private FakeClaims(SettlementWorkClaimService.Claim... claims) {
            queue.addAll(java.util.List.of(claims));
        }

        @Override
        public Optional<SettlementWorkClaimService.Claim> claimNext(String workerId) {
            claimCalls.incrementAndGet();
            SettlementWorkClaimService.Claim claim = queue.poll();
            if (claim != null) onClaim.apply(claim);
            return Optional.ofNullable(claim);
        }

        @Override
        public SettlementWorkClaimService.RetryResult recordTransientFailure(
                SettlementWorkClaimService.Claim claim,
                SettlementWorkClaimService.TransientFailure failure) {
            retryCalls.incrementAndGet();
            lastFailure = failure;
            return retryResults.getOrDefault(claim.id(), SettlementWorkClaimService.RetryResult.STALE_LEASE);
        }
    }
}
