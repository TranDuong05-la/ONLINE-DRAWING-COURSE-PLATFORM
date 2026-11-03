package com.drawingcourse.backend.service.settlement;

import com.drawingcourse.backend.repository.PaymentSettlementWorkClaimRepository;
import com.drawingcourse.backend.repository.PaymentSettlementWorkClaimRepository.Candidate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit claim primitive only. It is not scheduled and does not execute settlement. */
@Service
public class SettlementWorkClaimService {
    private final PaymentSettlementWorkClaimRepository repository;
    private final SettlementRetryPolicy retryPolicy;
    private final TransactionTemplate transaction;
    private final SettlementTimeoutProperties timeoutProperties;
    private final AtomicInteger categoryCursor = new AtomicInteger();

    @Autowired
    public SettlementWorkClaimService(PaymentSettlementWorkClaimRepository repository,
                                      PlatformTransactionManager transactionManager,
                                      SettlementTimeoutProperties timeoutProperties) {
        this.repository = Objects.requireNonNull(repository);
        this.timeoutProperties = Objects.requireNonNull(timeoutProperties);
        this.retryPolicy = SettlementRetryPolicy.fullJitter();
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED);
        this.transaction.setTimeout(Math.toIntExact(timeoutProperties.getClaimTransactionTimeout().toSeconds()));
    }

    /** The method returns only after the REQUIRED claim transaction commits. */
    public Optional<Claim> claimNext(String workerId) {
        requireNoAmbientTransaction();
        validateWorker(workerId);
        return transaction.execute(status -> {
            Instant now = repository.databaseUtcNow();
            int start = Math.floorMod(categoryCursor.getAndIncrement(), 3);
            for (int i = 0; i < 3; i++) {
                switch ((start + i) % 3) {
                    case 0 -> {
                        Optional<Claim> claim = claim(repository.lockReady(now), workerId, now);
                        if (claim.isPresent()) return claim;
                    }
                    case 1 -> {
                        Optional<Claim> claim = claim(repository.lockRetryWait(now), workerId, now);
                        if (claim.isPresent()) return claim;
                    }
                    case 2 -> {
                        Optional<Candidate> expired = repository.lockExpiredLease(now);
                        if (expired.isPresent()) {
                            return claim(expired, workerId, now);
                        }
                        Optional<Candidate> exhausted = repository.lockExhaustedLease(now);
                        if (exhausted.isPresent() && repository.exhaustExpired(exhausted.get(), now) != 1)
                            throw new IllegalStateException("Could not move exhausted lease to manual review");
                    }
                    default -> throw new IllegalStateException("Invalid work category");
                }
            }
            return Optional.empty();
        });
    }

    /** Fenced, bounded retry transition; call only after the processing transaction has rolled back. */
    public RetryResult recordTransientFailure(Claim claim, TransientFailure failure) {
        requireNoAmbientTransaction();
        Objects.requireNonNull(claim); Objects.requireNonNull(failure);
        return transaction.execute(status -> {
            Instant now = repository.databaseUtcNow();
            if (claim.attemptCount() >= 8) {
                return repository.exhaustAfterFailure(claim.id(), claim.leaseToken(),
                        "ATTEMPTS_EXHAUSTED", now) == 1 ? RetryResult.MANUAL_REVIEW : RetryResult.STALE_LEASE;
            }
            Duration delay = retryPolicy.delayForCommittedClaim(claim.attemptCount());
            return repository.scheduleRetry(claim.id(), claim.leaseToken(), failure.code(), now, now.plus(delay)) == 1
                    ? RetryResult.RETRY_SCHEDULED : RetryResult.STALE_LEASE;
        });
    }

    private Optional<Claim> claim(Optional<Candidate> candidate, String workerId, Instant now) {
        if (candidate.isEmpty()) return Optional.empty();
        Candidate item = candidate.get();
        if (item.attemptCount() >= 8) {
            if (repository.exhaustExpired(item, now) != 1) throw new IllegalStateException("Could not exhaust lease");
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        Instant until = now.plus(timeoutProperties.getLeaseDuration());
        if (repository.claim(item, token, workerId, now, until) != 1)
            throw new IllegalStateException("Locked settlement work item was not claimable");
        return Optional.of(new Claim(item.id(), item.paymentEventId(), token, workerId,
                item.attemptCount() + 1, item.retryGeneration(), now, until));
    }

    private static void validateWorker(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 64
                || !workerId.matches("[A-Za-z0-9._:-]+"))
            throw new IllegalArgumentException("workerId must be 1..64 ASCII identifier characters");
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Settlement work claim/retry requires its own short transaction");
    }

    public enum TransientFailure {
        TRANSIENT_DATABASE("TRANSIENT_DATABASE"), DEADLOCK("DEADLOCK"), LOCK_TIMEOUT("LOCK_TIMEOUT");
        private final String code;
        TransientFailure(String code) { this.code = code; }
        public String code() { return code; }
    }
    public enum RetryResult { RETRY_SCHEDULED, MANUAL_REVIEW, STALE_LEASE }
    public record Claim(int id, int paymentEventId, String leaseToken, String workerId, int attemptCount,
                        long retryGeneration, Instant claimedAt, Instant leaseUntil) { }
}
