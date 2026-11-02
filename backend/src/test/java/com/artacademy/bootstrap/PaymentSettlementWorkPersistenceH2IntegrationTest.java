package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.PaymentSettlementWorkActionRepository;
import com.drawingcourse.backend.repository.PaymentSettlementWorkItemRepository;
import com.drawingcourse.backend.repository.PaymentSettlementWorkClaimRepository;
import com.drawingcourse.backend.service.settlement.SettlementWorkClaimService;
import com.drawingcourse.backend.service.settlement.SettlementTimeoutProperties;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Hibernate-generated isolated H2 schema tests; these do not execute Flyway or prove MySQL locking. */
@SpringBootTest(classes = ArtAcademyApplication.class,
        properties = "spring.datasource.url=jdbc:h2:mem:paymentworktests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class PaymentSettlementWorkPersistenceH2IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-28T03:04:05.123456Z");

    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PaymentSettlementWorkItemRepository workItems;
    @Autowired private PaymentSettlementWorkClaimRepository claimRows;
    @Autowired private SettlementWorkClaimService claims;
    @Autowired private SettlementTimeoutProperties timeoutProperties;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private PaymentSettlementWorkActionRepository actions;
    @Autowired private JdbcTemplate jdbc;
    private final List<Integer> fixtureEventIds = new ArrayList<>();

    @AfterEach
    void removeThisTestFixtures() {
        for (Integer eventId : fixtureEventIds) {
            Integer attemptId = jdbc.queryForObject("SELECT attempt_id FROM payment_events WHERE id=?", Integer.class, eventId);
            Integer orderId = jdbc.queryForObject("SELECT order_id FROM payment_events WHERE id=?", Integer.class, eventId);
            Integer userId = jdbc.queryForObject("SELECT user_id FROM payment_events WHERE id=?", Integer.class, eventId);
            jdbc.update("DELETE FROM payment_settlement_work_actions WHERE work_item_id IN " +
                    "(SELECT id FROM payment_settlement_work_items WHERE payment_event_id=?)", eventId);
            jdbc.update("DELETE FROM payment_settlement_work_items WHERE payment_event_id=?", eventId);
            jdbc.update("DELETE FROM payment_events WHERE id=?", eventId);
            jdbc.update("DELETE FROM payment_attempts WHERE id=?", attemptId);
            jdbc.update("DELETE FROM order_items WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", orderId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
        fixtureEventIds.clear();
    }

    @Test
    void workItemAndOperatorActionMapAndReadBack() {
        var fixture = fixture();
        var workId = tx().execute(status -> workItems.saveAndFlush(
                PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, NOW)).getId());
        String actionKey = UUID.randomUUID().toString();
        var actionId = tx().execute(status -> actions.saveAndFlush(PaymentSettlementWorkActionJpaEntity.snapshot(
                workId, actionKey, PaymentSettlementWorkActionType.REQUEUE,
                PaymentSettlementWorkState.MANUAL_REVIEW, PaymentSettlementWorkState.READY,
                fixture.userId, PaymentSettlementWorkActionReason.EVIDENCE_REVERIFIED, (short) 8, 0L, NOW)).getId());

        var work = tx().execute(status -> workItems.findByPaymentEventId(fixture.eventId).orElseThrow());
        var action = tx().execute(status -> actions.findById(actionId).orElseThrow());
        assertEquals(PaymentSettlementWorkState.READY, work.getState());
        assertEquals((short) 0, work.getAttemptCount());
        assertEquals(0L, work.getRetryGeneration());
        assertEquals(NOW, work.getAvailableAt());
        assertEquals(NOW, work.getCreatedAt());
        assertEquals(NOW, work.getUpdatedAt());
        assertEquals(fixture.eventId, work.getPaymentEvent().getId());
        assertEquals(workId, action.getWorkItemId());
        assertEquals(actionKey, action.getActionKey());
        assertEquals(fixture.userId, action.getActor().getId());
        assertEquals((short) 8, action.getPriorAttemptCount().shortValue());
        assertEquals(0L, action.getPriorRetryGeneration());
        assertEquals(NOW, action.getCreatedAt());
    }

    @Test
    void timeoutFoundationDefaultsAreDisabledAndDoNotChangeSharedJdbcTemplate() {
        assertFalse(timeoutProperties.isEnabled());
        assertEquals(Duration.ofSeconds(120), timeoutProperties.getLeaseDuration());
        assertEquals(Duration.ofSeconds(60), timeoutProperties.getTransactionTimeout());
        assertEquals(Duration.ofSeconds(5), timeoutProperties.getClaimTransactionTimeout());
        assertEquals(-1, jdbc.getQueryTimeout(), "The shared application JdbcTemplate must remain untouched");
        JdbcTemplate settlementJdbc = (JdbcTemplate) org.springframework.test.util.ReflectionTestUtils
                .getField(claimRows, "jdbc");
        assertNotNull(settlementJdbc);
        assertEquals(5, settlementJdbc.getQueryTimeout(), "Only the settlement claim JDBC template is timed");
        assertFalse(applicationContext.containsBean("settlementCoordinator"));
    }

    @Test
    void leaseConfigurationAppliesToNewClaimsWithoutRewritingActiveLease() {
        Fixture firstFixture = fixture();
        int firstWorkId = tx().execute(status -> workItems.saveAndFlush(PaymentSettlementWorkItemJpaEntity.ready(
                firstFixture.eventId, claimRows.databaseUtcNow().minusSeconds(1))).getId());
        var first = claims.claimNext("lease-config-a").orElseThrow();
        assertEquals(firstWorkId, first.id());
        var persistedFirstLease = tx().execute(status -> workItems.findById(firstWorkId).orElseThrow().getLeaseUntil());
        Duration original = timeoutProperties.getLeaseDuration();
        try {
            timeoutProperties.setLeaseDuration(Duration.ofSeconds(180));
            Fixture secondFixture = fixture();
            tx().executeWithoutResult(status -> workItems.saveAndFlush(PaymentSettlementWorkItemJpaEntity.ready(
                    secondFixture.eventId, claimRows.databaseUtcNow().minusSeconds(1))));
            var second = claims.claimNext("lease-config-b").orElseThrow();
            assertEquals(Duration.ofSeconds(180), Duration.between(second.claimedAt(), second.leaseUntil()));
            assertEquals(persistedFirstLease,
                    tx().execute(status -> workItems.findById(firstWorkId).orElseThrow().getLeaseUntil()));
        } finally {
            timeoutProperties.setLeaseDuration(original);
        }
    }

    @Test
    void oneWorkItemPerEventAndDuplicateReplayReadsCanonicalRow() {
        var fixture = fixture();
        var first = tx().execute(status -> workItems.saveAndFlush(
                PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, NOW)).getId());
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status ->
                workItems.saveAndFlush(PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, NOW.plusSeconds(1)))));
        var canonical = tx().execute(status -> workItems.findByPaymentEventId(fixture.eventId).orElseThrow());
        assertEquals(first, canonical.getId());
        assertEquals(PaymentSettlementWorkState.READY, canonical.getState());
        assertEquals(1L, count("payment_settlement_work_items", fixture.eventId));
    }

    @Test
    void eventWorkAndActionForeignKeysRejectMissingRoutes() {
        var fixture = fixture();
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status ->
                workItems.saveAndFlush(PaymentSettlementWorkItemJpaEntity.ready(Integer.MAX_VALUE, NOW))));

        var workId = tx().execute(status -> workItems.saveAndFlush(
                PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, NOW)).getId());
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status ->
                actions.saveAndFlush(PaymentSettlementWorkActionJpaEntity.snapshot(
                        Integer.MAX_VALUE, UUID.randomUUID().toString(), PaymentSettlementWorkActionType.REQUEUE,
                        PaymentSettlementWorkState.MANUAL_REVIEW, PaymentSettlementWorkState.READY,
                        fixture.userId, PaymentSettlementWorkActionReason.ADAPTER_RESTORED,
                        (short) 0, 0L, NOW))));
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status ->
                actions.saveAndFlush(PaymentSettlementWorkActionJpaEntity.snapshot(
                        workId, UUID.randomUUID().toString(), PaymentSettlementWorkActionType.REQUEUE,
                        PaymentSettlementWorkState.MANUAL_REVIEW, PaymentSettlementWorkState.READY,
                        Integer.MAX_VALUE, PaymentSettlementWorkActionReason.ADAPTER_RESTORED,
                        (short) 0, 0L, NOW))));
    }

    @Test
    void checkConstraintsRejectInvalidStateLeaseAndAttemptCount() {
        var fixture = fixture();
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status -> jdbc.update(
                "INSERT INTO payment_settlement_work_items(payment_event_id,state,attempt_count,retry_generation,available_at,created_at,updated_at) VALUES (?, 'BOGUS', 0, 0, ?, ?, ?)",
                fixture.eventId, NOW, NOW, NOW)));
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status -> jdbc.update(
                "INSERT INTO payment_settlement_work_items(payment_event_id,state,attempt_count,retry_generation,created_at,updated_at) VALUES (?, 'LEASED', 1, 0, ?, ?)",
                fixture.eventId, NOW, NOW)));

        var workId = tx().execute(status -> workItems.saveAndFlush(
                PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, NOW)).getId());
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status -> jdbc.update(
                "UPDATE payment_settlement_work_items SET attempt_count=9 WHERE id=?", workId)));
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status -> jdbc.update(
                "UPDATE payment_settlement_work_items SET retry_generation=-1 WHERE id=?", workId)));
        assertEquals((short) 0, workItems.findById(workId).orElseThrow().getAttemptCount().shortValue());
    }

    @Test
    void operatorActionUniqueKeyIsIdempotencyBoundaryAndInvalidTransitionIsRejected() {
        var fixture = fixture();
        var workId = tx().execute(status -> workItems.saveAndFlush(
                PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, NOW)).getId());
        String actionKey = UUID.randomUUID().toString();
        var firstId = tx().execute(status -> actions.saveAndFlush(PaymentSettlementWorkActionJpaEntity.snapshot(
                workId, actionKey, PaymentSettlementWorkActionType.REQUEUE,
                PaymentSettlementWorkState.MANUAL_REVIEW, PaymentSettlementWorkState.READY,
                fixture.userId, PaymentSettlementWorkActionReason.TRANSIENT_CAUSE_RESOLVED,
                (short) 8, 0L, NOW)).getId());
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status ->
                actions.saveAndFlush(PaymentSettlementWorkActionJpaEntity.snapshot(
                        workId, actionKey, PaymentSettlementWorkActionType.REQUEUE,
                        PaymentSettlementWorkState.MANUAL_REVIEW, PaymentSettlementWorkState.READY,
                        fixture.userId, PaymentSettlementWorkActionReason.TRANSIENT_CAUSE_RESOLVED,
                        (short) 8, 0L, NOW.plusSeconds(1)))));
        assertEquals(firstId, tx().execute(status -> actions.findByWorkItemIdAndActionKey(workId, actionKey)
                .orElseThrow().getId()));
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status -> jdbc.update(
                "INSERT INTO payment_settlement_work_actions(work_item_id,action_key,action_type,from_state,to_state,actor_user_id,reason_code,prior_attempt_count,prior_retry_generation,created_at) VALUES (?,?,'REQUEUE','READY','COMPLETED',?,'TRANSIENT_CAUSE_RESOLVED',0,0,?)",
                workId, UUID.randomUUID().toString(), fixture.userId, NOW)));
        assertThrows(IllegalArgumentException.class, () -> PaymentSettlementWorkActionJpaEntity.snapshot(
                workId, UUID.randomUUID().toString(), PaymentSettlementWorkActionType.REQUEUE,
                PaymentSettlementWorkState.READY, PaymentSettlementWorkState.COMPLETED,
                fixture.userId, PaymentSettlementWorkActionReason.TRANSIENT_CAUSE_RESOLVED,
                (short) 0, 0L, NOW));
    }

    @Test
    void retryGenerationMapsAsLongAndRejectsOverflow() {
        var fixture = fixture();
        long maxSafePersistedValue = Long.MAX_VALUE;
        var workId = tx().execute(status -> {
            var row = PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, NOW);
            var saved = workItems.saveAndFlush(row);
            jdbc.update("UPDATE payment_settlement_work_items SET retry_generation=? WHERE id=?",
                    maxSafePersistedValue, saved.getId());
            entityManager.clear();
            return saved.getId();
        });
        assertEquals(maxSafePersistedValue,
                tx().execute(status -> workItems.findById(workId).orElseThrow().getRetryGeneration()).longValue());
        assertEquals(1L, PaymentSettlementWorkItemJpaEntity.nextRetryGeneration(0L));
        assertThrows(IllegalStateException.class,
                () -> PaymentSettlementWorkItemJpaEntity.nextRetryGeneration(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentSettlementWorkItemJpaEntity.nextRetryGeneration(-1L));
    }

    @Test
    void workAndActionWritesRollbackTogether() {
        var fixture = fixture();
        RowIds ids = tx().execute(status -> {
            var work = workItems.saveAndFlush(PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, NOW));
            var action = actions.saveAndFlush(PaymentSettlementWorkActionJpaEntity.snapshot(
                    work.getId(), UUID.randomUUID().toString(), PaymentSettlementWorkActionType.CLOSE_NOT_ELIGIBLE,
                    PaymentSettlementWorkState.MANUAL_REVIEW, PaymentSettlementWorkState.CLOSED_NOT_ELIGIBLE,
                    fixture.userId, PaymentSettlementWorkActionReason.REVIEWED_NOT_ELIGIBLE,
                    (short) 0, 0L, NOW));
            status.setRollbackOnly();
            return new RowIds(work.getId(), action.getId());
        });
        assertNotNull(ids);
        assertTrue(Boolean.TRUE.equals(tx().execute(status -> workItems.findById(ids.workId()).isEmpty())));
        assertTrue(Boolean.TRUE.equals(tx().execute(status -> actions.findById(ids.actionId()).isEmpty())));
    }

    @Test
    void readyItemIsClaimedOnceWithFreshLeaseAndIncrementedAttempt() {
        var fixture = fixture();
        int workId = tx().execute(status -> workItems.saveAndFlush(
                PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, claimRows.databaseUtcNow().minusSeconds(1))).getId());
        var first = claims.claimNext("worker-a").orElseThrow();
        assertEquals(workId, first.id());
        assertEquals(fixture.eventId, first.paymentEventId());
        assertEquals(1, first.attemptCount());
        assertEquals("worker-a", first.workerId());
        assertEquals(36, first.leaseToken().length());
        assertEquals(Duration.ofSeconds(120), Duration.between(first.claimedAt(), first.leaseUntil()));
        assertTrue(claims.claimNext("worker-b").isEmpty());

        jdbc.update("UPDATE payment_settlement_work_items SET lease_until=? WHERE id=?",
                LocalDateTime.ofInstant(first.claimedAt().minusSeconds(1), ZoneOffset.UTC), workId);
        var reclaimed = claims.claimNext("worker-b").orElseThrow();
        assertEquals(2, reclaimed.attemptCount());
        assertNotEquals(first.leaseToken(), reclaimed.leaseToken());
        assertEquals(PaymentSettlementWorkState.LEASED,
                tx().execute(status -> workItems.findById(workId).orElseThrow().getState()));
    }

    @Test
    void retryWaitIsClaimableOnlyWhenDue() {
        var fixture = fixture();
        var now = claimRows.databaseUtcNow();
        int workId = tx().execute(status -> workItems.saveAndFlush(
                PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, now.plusSeconds(90))).getId());
        jdbc.update("UPDATE payment_settlement_work_items SET state='RETRY_WAIT' WHERE id=?", workId);
        assertTrue(claims.claimNext("worker").isEmpty());
        jdbc.update("UPDATE payment_settlement_work_items SET available_at=? WHERE id=?",
                LocalDateTime.ofInstant(claimRows.databaseUtcNow().minusSeconds(1), ZoneOffset.UTC), workId);
        assertEquals(workId, claims.claimNext("worker").orElseThrow().id());
    }

    @Test
    void expiredLeaseAtBudgetMovesToReviewAndUnexpiredLeaseIsNotClaimed() {
        var fixture = fixture();
        Instant now = claimRows.databaseUtcNow();
        int unexpired = tx().execute(status -> workItems.saveAndFlush(
                PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, now)).getId());
        jdbc.update("UPDATE payment_settlement_work_items SET state='LEASED',available_at=NULL,lease_token=?,lease_owner=?,lease_until=? WHERE id=?",
                UUID.randomUUID().toString(), "worker", LocalDateTime.ofInstant(now.plusSeconds(30), ZoneOffset.UTC), unexpired);
        assertTrue(claims.claimNext("other").isEmpty());
        jdbc.update("UPDATE payment_settlement_work_items SET lease_until=?,attempt_count=8 WHERE id=?",
                LocalDateTime.ofInstant(claimRows.databaseUtcNow().minusSeconds(1), ZoneOffset.UTC), unexpired);
        assertTrue(claims.claimNext("other").isEmpty());
        var reviewed = tx().execute(status -> workItems.findById(unexpired).orElseThrow());
        assertEquals(PaymentSettlementWorkState.MANUAL_REVIEW, reviewed.getState());
        assertEquals("LEASE_EXPIRED", reviewed.getLastFailureCode());
        assertNull(reviewed.getLeaseToken());
    }

    @Test
    void transientFailureIsFencedAndUsesRetryWaitWithoutAllowingStaleLease() {
        var fixture = fixture();
        int workId = tx().execute(status -> workItems.saveAndFlush(
                PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId, claimRows.databaseUtcNow().minusSeconds(1))).getId());
        var stale = claims.claimNext("worker-a").orElseThrow();
        jdbc.update("UPDATE payment_settlement_work_items SET lease_until=? WHERE id=?",
                LocalDateTime.ofInstant(claimRows.databaseUtcNow().minusSeconds(1), ZoneOffset.UTC), workId);
        var current = claims.claimNext("worker-b").orElseThrow();
        assertEquals(SettlementWorkClaimService.RetryResult.STALE_LEASE,
                claims.recordTransientFailure(stale, SettlementWorkClaimService.TransientFailure.DEADLOCK));
        assertEquals(SettlementWorkClaimService.RetryResult.RETRY_SCHEDULED,
                claims.recordTransientFailure(current, SettlementWorkClaimService.TransientFailure.DEADLOCK));
        var row = tx().execute(status -> workItems.findById(workId).orElseThrow());
        assertEquals(PaymentSettlementWorkState.RETRY_WAIT, row.getState());
        assertEquals("DEADLOCK", row.getLastFailureCode());
        assertNull(row.getLeaseToken());
    }

    @Test
    void retryPolicyUsesInjectableFullJitterAndCapsAtFifteenMinutes() {
        var zero = new com.drawingcourse.backend.service.settlement.SettlementRetryPolicy(bound -> 0);
        assertEquals(Duration.ZERO, zero.delayForCommittedClaim(1));
        var max = new com.drawingcourse.backend.service.settlement.SettlementRetryPolicy(bound -> bound - 1);
        assertEquals(Duration.ofSeconds(5), max.delayForCommittedClaim(1));
        assertEquals(Duration.ofMinutes(15), max.delayForCommittedClaim(8));
        assertThrows(IllegalArgumentException.class, () -> max.delayForCommittedClaim(0));
    }

    @Test
    void fencedMutationsRequireCorrectIdStateTokenAndUnexpiredDatabaseTime() {
        Fixture validFixture = fixture();
        Fixture stateFixture = fixture();
        Fixture expiredFixture = fixture();
        Instant sampled = claimRows.databaseUtcNow();
        String validToken = UUID.randomUUID().toString();
        String stateToken = UUID.randomUUID().toString();
        String expiredToken = UUID.randomUUID().toString();
        int validId = leasedWork(validFixture.eventId, validToken, sampled.plusSeconds(30), 1);
        int stateId = leasedWork(stateFixture.eventId, stateToken, sampled.plusSeconds(30), 1);
        int expiredId = leasedWork(expiredFixture.eventId, expiredToken, sampled.minusSeconds(1), 1);

        assertEquals(0, tx().<Integer>execute(s -> claimRows.scheduleRetry(validId + 10_000, validToken,
                "DEADLOCK", sampled, sampled.plusSeconds(1))));
        assertEquals(0, tx().<Integer>execute(s -> claimRows.scheduleRetry(validId, UUID.randomUUID().toString(),
                "DEADLOCK", sampled, sampled.plusSeconds(1))));
        assertEquals(1, tx().<Integer>execute(s -> claimRows.scheduleRetry(validId, validToken,
                "DEADLOCK", sampled, sampled.plusSeconds(1))));
        var retried = tx().execute(s -> workItems.findById(validId).orElseThrow());
        assertEquals(PaymentSettlementWorkState.RETRY_WAIT, retried.getState());
        assertEquals("DEADLOCK", retried.getLastFailureCode());
        assertNull(retried.getLeaseToken());

        jdbc.update("UPDATE payment_settlement_work_items SET state='MANUAL_REVIEW',available_at=NULL,lease_token=NULL,lease_owner=NULL,lease_until=NULL WHERE id=?", stateId);
        assertEquals(0, tx().<Integer>execute(s -> claimRows.scheduleRetry(stateId, stateToken,
                "DEADLOCK", sampled, sampled.plusSeconds(1))));
        assertEquals(PaymentSettlementWorkState.MANUAL_REVIEW,
                tx().execute(s -> workItems.findById(stateId).orElseThrow().getState()));

        var before = tx().execute(s -> workItems.findById(expiredId).orElseThrow());
        assertEquals(0, tx().<Integer>execute(s -> claimRows.scheduleRetry(expiredId, expiredToken,
                "DEADLOCK", sampled, sampled.plusSeconds(1))));
        var after = tx().execute(s -> workItems.findById(expiredId).orElseThrow());
        assertEquals(PaymentSettlementWorkState.LEASED, after.getState());
        assertEquals(before.getLeaseToken(), after.getLeaseToken());
        assertEquals(before.getLastFailureCode(), after.getLastFailureCode());
    }

    @Test
    void previouslySampledTimeCannotAuthorizeMutationAfterExpiry() {
        Fixture fixture = fixture();
        Instant sampled = claimRows.databaseUtcNow();
        Instant expiresAt = sampled.plusMillis(350);
        String token = UUID.randomUUID().toString();
        int workId = leasedWork(fixture.eventId, token, expiresAt, 1);
        waitUntilDatabaseTime(expiresAt);
        assertEquals(0, tx().<Integer>execute(s -> claimRows.scheduleRetry(workId, token,
                "DEADLOCK", sampled, sampled.plusSeconds(5))));
        var row = tx().execute(s -> workItems.findById(workId).orElseThrow());
        assertEquals(PaymentSettlementWorkState.LEASED, row.getState());
        assertEquals(token, row.getLeaseToken());
    }

    @Test
    void rowLockWaitCompletesBeforeFencedUpdateChecksDatabaseTime() throws Exception {
        Fixture fixture = fixture();
        Instant sampled = claimRows.databaseUtcNow();
        Instant expiresAt = sampled.plusSeconds(2);
        String token = UUID.randomUUID().toString();
        int workId = leasedWork(fixture.eventId, token, expiresAt, 1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> tx().execute(s -> {
                jdbc.queryForObject("SELECT id FROM payment_settlement_work_items WHERE id=? FOR UPDATE", Integer.class, workId);
                locked.countDown();
                awaitRelease(release);
                return null;
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var updater = pool.submit(() -> tx().execute(s ->
                    claimRows.scheduleRetry(workId, token, "DEADLOCK", sampled, sampled.plusSeconds(5))));
            try {
                waitUntilDatabaseTime(expiresAt);
            } finally {
                release.countDown();
            }
            assertEquals(0, updater.get(8, TimeUnit.SECONDS));
            holder.get(8, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        var row = tx().execute(s -> workItems.findById(workId).orElseThrow());
        assertEquals(PaymentSettlementWorkState.LEASED, row.getState());
        assertEquals(token, row.getLeaseToken());
    }

    @Test
    void exhaustedAttemptMutationRequiresOneLiveLeaseAndPreservesManualReview() {
        Fixture fixture = fixture();
        Instant now = claimRows.databaseUtcNow();
        String token = UUID.randomUUID().toString();
        int workId = leasedWork(fixture.eventId, token, now.plusSeconds(30), 8);
        assertEquals(1, tx().<Integer>execute(s -> claimRows.exhaustAfterFailure(workId, token,
                "ATTEMPTS_EXHAUSTED", now)));
        assertEquals(0, tx().<Integer>execute(s -> claimRows.exhaustAfterFailure(workId, token,
                "ATTEMPTS_EXHAUSTED", now)));
        var row = tx().execute(s -> workItems.findById(workId).orElseThrow());
        assertEquals(PaymentSettlementWorkState.MANUAL_REVIEW, row.getState());
        assertEquals("ATTEMPTS_EXHAUSTED", row.getLastFailureCode());
        assertNull(row.getLeaseToken());
    }

    private int leasedWork(Integer eventId, String token, Instant leaseUntil, int attempts) {
        int id = tx().execute(s -> workItems.saveAndFlush(PaymentSettlementWorkItemJpaEntity.ready(
                eventId, claimRows.databaseUtcNow().minusSeconds(1))).getId());
        jdbc.update("UPDATE payment_settlement_work_items SET state='LEASED',attempt_count=?,available_at=NULL,lease_token=?,lease_owner=?,lease_until=? WHERE id=?",
                attempts, token, "fence-test", LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC), id);
        return id;
    }

    private void waitUntilDatabaseTime(Instant deadline) {
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (claimRows.databaseUtcNow().isBefore(deadline)) {
            if (System.nanoTime() >= end) throw new AssertionError("Database clock did not reach lease deadline");
            try {
                new CountDownLatch(1).await(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the database clock", ex);
            }
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Test row lock timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private Fixture fixture() {
        return tx().execute(status -> {
            var user = User.builder().fullName("Settlement Outbox Fixture")
                    .email("v9-outbox-" + UUID.randomUUID() + "@example.test")
                    .passwordHash("test-only-hash").build();
            entityManager.persist(user);
            entityManager.flush();

            var order = OrderJpaEntity.fromSnapshot(Order.create(user.getId(),
                    List.of(new OrderLineSnapshot(OrderItemType.COURSE, 88, "Outbox fixture", 1, 12_345, true)),
                    0, Optional.empty(), Clock.fixed(NOW.minusSeconds(30), ZoneOffset.UTC)));
            entityManager.persist(order);
            entityManager.flush();
            var stored = new OrderPersistenceMapper().toDomain(order);
            var attempt = PaymentAttemptJpaEntity.newSnapshot(PaymentAttempt.create(order.getId(), stored,
                    "vnpay", "outbox-" + UUID.randomUUID(), Clock.fixed(NOW.minusSeconds(20), ZoneOffset.UTC)));
            entityManager.persist(attempt);
            entityManager.flush();
            var event = new PaymentEvent("vnpay", "outbox-event-" + UUID.randomUUID(), attempt.getMerchantReference(),
                    "outbox-txn", attempt.getExpectedAmountVnd(), "VND", PaymentEvent.Outcome.SUCCEEDED,
                    NOW.minusSeconds(2), NOW.minusSeconds(1), "a".repeat(64), "verified-test");
            var audit = new PaymentEventAudit(null, attempt.getId(), order.getId(), user.getId(), event,
                    event.fingerprint(), PaymentStatus.PENDING, PaymentStatus.SUCCEEDED,
                    PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE,
                    OrderStatus.PENDING_PAYMENT);
            var eventRow = PaymentEventJpaEntity.newSnapshot(audit);
            entityManager.persist(eventRow);
            entityManager.flush();
            fixtureEventIds.add(eventRow.getId());
            return new Fixture(user.getId(), eventRow.getId());
        });
    }

    private long count(String table, Integer eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE payment_event_id=?", Long.class, eventId);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private record Fixture(Integer userId, Integer eventId) { }
    private record RowIds(Integer workId, Integer actionId) { }
}
