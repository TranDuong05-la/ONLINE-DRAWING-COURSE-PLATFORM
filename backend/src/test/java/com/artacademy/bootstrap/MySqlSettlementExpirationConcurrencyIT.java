package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.payment.vnpay.IpnClassification;
import com.artacademy.payment.vnpay.IpnReconciliationResult;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.settlement.SettlementWorkClaimService;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import com.drawingcourse.backend.service.StoredOrder;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.expiration.*;
import com.drawingcourse.backend.service.settlement.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in MySQL concurrency verification. TargetGuard refuses every target except drawing_course_it. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles({"test", "mysql-it"})
@ContextConfiguration(initializers = MySqlSchemaCompatibilityIT.TargetGuard.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=8",
        "spring.datasource.hikari.maximum-pool-size=8"
})
class MySqlSettlementExpirationConcurrencyIT {
    private static final Instant NOW = Instant.parse("2026-10-28T05:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(1800);
    private static final Instant PAID_AT = NOW.plusSeconds(5);
    private static final Instant RECEIVED_AT = NOW.plusSeconds(6);
    private static final int COURSE_ID = 771;
    private static final int PRODUCT_ID = 772;
    private static final long COURSE_PRICE = 10_000L;
    private static final long PRODUCT_PRICE = 20_000L;
    private static final long VOUCHER_DISCOUNT = 1_000L;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentEventRepository events;
    @Autowired private PaymentEventConflictRepository conflicts;
    @Autowired private PaymentSettlementRepository settlements;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JpaSettlementLedgerAdapter realLedger;
    @Autowired private JpaVoucherReservationService vouchers;
    @Autowired private PaymentSettlementWorkItemRepository workItems;
    @Autowired private PaymentSettlementWorkClaimRepository workClaims;
    @Autowired private SettlementWorkClaimService claimService;

    private final List<Fixture> fixtures = new CopyOnWriteArrayList<>();
    private TestParticipants participants;

    @BeforeEach
    void verifyGuardedTargetAndCreateTestAdapterTable() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("drawing_course_it");
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualToIgnoringCase("REPEATABLE-READ");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IN ('1','2','3','4','5','6','7','8')", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version='9'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_settlements'", Integer.class)).isEqualTo(1);
        jdbc.execute("CREATE TABLE IF NOT EXISTS payment_lifecycle_it_effects (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, order_id INT NOT NULL, kind VARCHAR(32) NOT NULL, item_id INT NOT NULL, UNIQUE KEY uk_payment_lifecycle_it_effect (order_id,kind,item_id)) ENGINE=InnoDB");
        participants = new TestParticipants(jdbc);
    }

    @AfterEach
    void cleanOnlyThisTestFixtures() {
        for (Fixture fixture : fixtures) {
            Integer orderId = fixture.orderId();
            jdbc.update("DELETE FROM payment_settlement_work_items WHERE payment_event_id IN (SELECT id FROM payment_events WHERE order_id=?)", orderId);
            jdbc.update("DELETE FROM payment_lifecycle_it_effects WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_reconciliation_cases WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_event_conflicts WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_settlements WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_events WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_attempts WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM voucher_claims WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM order_items WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", orderId);
            jdbc.update("DELETE FROM voucher_user_usage WHERE voucher_id=? AND user_id=?", fixture.voucherId(), fixture.userId());
            jdbc.update("DELETE FROM vouchers WHERE id=?", fixture.voucherId());
            jdbc.update("DELETE FROM users WHERE id=?", fixture.userId());
        }
        fixtures.clear();
    }

    @Test
    void concurrentSettlementAndDuplicateIpnReplayCreateOneSettlementAndConsumeVoucherOnce() throws Exception {
        Fixture fixture = fixture(PaymentStatus.SUCCEEDED, true);
        SettlementCoordinator coordinator = settlement(participants, realLedger);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<SettlementResult> first = pool.submit(() -> { await(start); return coordinator.settle(fixture.success()); });
            Future<SettlementResult> second = pool.submit(() -> { await(start); return coordinator.settle(fixture.success()); });
            start.countDown();
            List<SettlementResult.Outcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS).outcome(), second.get(10, TimeUnit.SECONDS).outcome());
            assertThat(outcomes).contains(SettlementResult.Outcome.SETTLED, SettlementResult.Outcome.ALREADY_SETTLED);
            assertThat(settlements.findByOrderId(fixture.orderId())).isPresent();
            assertThat(voucherValue("reserved_count", fixture.voucherId())).isZero();
            assertThat(voucherValue("redeemed_count", fixture.voucherId())).isEqualTo(1L);
            assertThat(userVoucherValue("reserved_count", fixture)).isZero();
            assertThat(userVoucherValue("redeemed_count", fixture)).isEqualTo(1L);
            assertThat(effectCount(fixture, "course-confirm")).isEqualTo(1L);
            assertThat(effectCount(fixture, "inventory-confirm")).isEqualTo(1L);
        }
    }

    @Test
    void sequentialDuplicateIpnReplayDoesNotRepeatSettlementVoucherOrFulfillmentEffects() {
        Fixture fixture = fixture(PaymentStatus.SUCCEEDED, true);
        SettlementCoordinator coordinator = settlement(participants, realLedger);

        SettlementResult first = coordinator.settle(fixture.success());
        SettlementResult replay = coordinator.settle(fixture.success());

        assertThat(first.outcome()).isEqualTo(SettlementResult.Outcome.SETTLED);
        assertThat(replay.outcome()).isEqualTo(SettlementResult.Outcome.ALREADY_SETTLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Long.class, fixture.orderId())).isEqualTo(1L);
        assertThat(voucherValue("redeemed_count", fixture.voucherId())).isEqualTo(1L);
        assertThat(effectCount(fixture, "course-confirm")).isEqualTo(1L);
        assertThat(effectCount(fixture, "inventory-confirm")).isEqualTo(1L);
    }

    @Test
    void settlementAtExclusiveDeadlineWinsOnlyAsReviewSafeSettlementAndExpirationDoesNotRelease() throws Exception {
        Fixture fixture = fixture(PaymentStatus.SUCCEEDED, true);
        SettlementCoordinator coordinator = settlement(participants, realLedger);
        OrderExpirationCoordinator expiration = expiration(participants, Clock.fixed(DEADLINE, ZoneOffset.UTC));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<SettlementResult> settle = pool.submit(() -> { await(start); return coordinator.settle(fixture.success()); });
            Future<ExpirationResult> expire = pool.submit(() -> { await(start); return expiration.expire(fixture.orderId()); });
            start.countDown();
            SettlementResult settlementResult = settle.get(10, TimeUnit.SECONDS);
            ExpirationResult expirationResult = expire.get(10, TimeUnit.SECONDS);
            assertThat(settlementResult.outcome()).isIn(SettlementResult.Outcome.SETTLED, SettlementResult.Outcome.ALREADY_SETTLED);
            assertThat(expirationResult.outcome()).isIn(ExpirationResult.Outcome.REQUIRES_REVIEW, ExpirationResult.Outcome.NOT_ELIGIBLE);
            assertThat(settlements.findByOrderId(fixture.orderId())).isPresent();
            assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(voucherValue("redeemed_count", fixture.voucherId())).isEqualTo(1L);
            assertThat(effectCount(fixture, "course-release")).isZero();
            assertThat(effectCount(fixture, "inventory-release")).isZero();
        } finally { shutdown(pool); }
    }

    @Test
    void concurrentExpirationReleasesReservationsAndVoucherExactlyOnce() throws Exception {
        Fixture fixture = fixture(PaymentStatus.FAILED, false);
        OrderExpirationCoordinator coordinator = expiration(participants, Clock.fixed(DEADLINE, ZoneOffset.UTC));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<ExpirationResult> first = pool.submit(() -> { await(start); return coordinator.expire(fixture.orderId()); });
            Future<ExpirationResult> second = pool.submit(() -> { await(start); return coordinator.expire(fixture.orderId()); });
            start.countDown();
            List<ExpirationResult.Outcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS).outcome(), second.get(10, TimeUnit.SECONDS).outcome());
            assertThat(outcomes).contains(ExpirationResult.Outcome.EXPIRED);
            assertThat(outcomes).containsAnyOf(ExpirationResult.Outcome.ALREADY_EXPIRED, ExpirationResult.Outcome.NOT_ELIGIBLE);
            assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.EXPIRED);
            assertThat(voucherValue("reserved_count", fixture.voucherId())).isZero();
            assertThat(voucherValue("redeemed_count", fixture.voucherId())).isZero();
            assertThat(userVoucherValue("reserved_count", fixture)).isZero();
            assertThat(effectCount(fixture, "course-release")).isEqualTo(1L);
            assertThat(effectCount(fixture, "inventory-release")).isEqualTo(1L);
        }
    }

    @Test
    void expirationWaitsForCommittedUnknownPaymentEventAndRequiresReview() throws Exception {
        Fixture fixture = fixture(PaymentStatus.FAILED, false);
        assertExpirationWaitsForEvidenceCommit(fixture, false);
        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(voucherValue("reserved_count", fixture.voucherId())).isEqualTo(1L);
        assertThat(effectCount(fixture, "course-release")).isZero();
    }

    @Test
    void expirationWaitsForCommittedPaymentEventConflictAndRequiresReview() throws Exception {
        Fixture fixture = fixture(PaymentStatus.FAILED, true, PaymentEvent.Outcome.FAILED);
        assertExpirationWaitsForEvidenceCommit(fixture, true);
        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(voucherValue("reserved_count", fixture.voucherId())).isEqualTo(1L);
        assertThat(effectCount(fixture, "course-release")).isZero();
    }

    @Test
    void courseConfirmationFailureRollsBackOrderVoucherInventoryMarkersAndLedger() {
        Fixture fixture = fixture(PaymentStatus.SUCCEEDED, true);
        participants.failAt = "course-confirm";
        assertThatThrownBy(() -> settlement(participants, realLedger).settle(fixture.success()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(settlements.findByOrderId(fixture.orderId())).isEmpty();
        assertReservedVoucher(fixture);
        assertThat(effectCount(fixture, "course-confirm")).isZero();
        assertThat(effectCount(fixture, "inventory-confirm")).isZero();
    }

    @Test
    void inventoryConfirmationFailureRollsBackOrderVoucherCourseMarkersAndLedger() {
        Fixture fixture = fixture(PaymentStatus.SUCCEEDED, true);
        participants.failAt = "inventory-confirm";
        assertThatThrownBy(() -> settlement(participants, realLedger).settle(fixture.success()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(settlements.findByOrderId(fixture.orderId())).isEmpty();
        assertReservedVoucher(fixture);
        assertThat(effectCount(fixture, "course-confirm")).isZero();
        assertThat(effectCount(fixture, "inventory-confirm")).isZero();
    }

    @Test
    void ledgerInsertionFailureRollsBackSettlementOrderVoucherAndTestAdapterEffects() {
        Fixture fixture = fixture(PaymentStatus.SUCCEEDED, true);
        SettlementLedgerPort failAfterInsert = new SettlementLedgerPort() {
            @Override public Optional<SettlementReference> findForUpdateByOrderId(Integer orderId) {
                return realLedger.findForUpdateByOrderId(orderId);
            }
            @Override public SettlementReference insert(Integer orderId, Integer attemptId, Integer eventId,
                    Instant paidAt, Instant settledAt) {
                realLedger.insert(orderId, attemptId, eventId, paidAt, settledAt);
                throw new IllegalStateException("Injected failure after settlement INSERT");
            }
        };
        assertThatThrownBy(() -> settlement(participants, failAfterInsert).settle(fixture.success()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(settlements.findByOrderId(fixture.orderId())).isEmpty();
        assertReservedVoucher(fixture);
        assertThat(effectCount(fixture, "course-confirm")).isZero();
        assertThat(effectCount(fixture, "inventory-confirm")).isZero();
    }

    @Test
    void claimedSettlementCommitsLedgerVoucherParticipantsAndWorkTogetherAndReplayIsIdempotent() {
        ClaimedFixture fixture = claimedFixture();
        SettlementCoordinator coordinator = claimedSettlement(participants, realLedger, vouchers);

        SettlementResult first = coordinator.executeClaimedWork(fixture.claim());
        SettlementResult replay = coordinator.executeClaimedWork(fixture.claim());

        assertThat(first.outcome()).isEqualTo(SettlementResult.Outcome.SETTLED);
        assertThat(replay.outcome()).isEqualTo(SettlementResult.Outcome.ALREADY_SETTLED);
        assertThat(workState(fixture.workItemId())).isEqualTo("COMPLETED");
        assertThat(orderStatus(fixture.fixture())).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Long.class,
                fixture.fixture().orderId())).isEqualTo(1L);
        assertThat(voucherValue("reserved_count", fixture.fixture().voucherId())).isZero();
        assertThat(voucherValue("redeemed_count", fixture.fixture().voucherId())).isEqualTo(1L);
        assertThat(userVoucherValue("reserved_count", fixture.fixture())).isZero();
        assertThat(userVoucherValue("redeemed_count", fixture.fixture())).isEqualTo(1L);
        assertThat(effectCount(fixture.fixture(), "course-confirm")).isEqualTo(1L);
        assertThat(effectCount(fixture.fixture(), "inventory-confirm")).isEqualTo(1L);
    }

    @Test
    void claimedVoucherFailureRollsBackOrderParticipantsLedgerAndWork() {
        ClaimedFixture fixture = claimedFixture();
        VoucherConsumptionPort failAfterVoucherWrite = (userId, orderId, reference, paidAt, observedAt) -> {
            vouchers.consumeForOrder(userId, orderId, reference, paidAt, observedAt);
            throw new IllegalStateException("Injected voucher participant failure");
        };

        SettlementResult result = claimedSettlement(participants, realLedger, failAfterVoucherWrite)
                .executeClaimedWork(fixture.claim());

        assertThat(result.outcome()).isEqualTo(SettlementResult.Outcome.REQUIRES_REVIEW);
        assertClaimedRollback(fixture);
    }

    @Test
    void claimedProductConfirmationFailureRollsBackEveryEffect() {
        ClaimedFixture fixture = claimedFixture();
        participants.failAt = "inventory-confirm";

        SettlementResult result = claimedSettlement(participants, realLedger, vouchers)
                .executeClaimedWork(fixture.claim());

        assertThat(result.outcome()).isEqualTo(SettlementResult.Outcome.REQUIRES_REVIEW);
        assertClaimedRollback(fixture);
    }

    @Test
    void claimedCourseConfirmationFailureRollsBackEveryEffect() {
        ClaimedFixture fixture = claimedFixture();
        participants.failAt = "course-confirm";

        SettlementResult result = claimedSettlement(participants, realLedger, vouchers)
                .executeClaimedWork(fixture.claim());

        assertThat(result.outcome()).isEqualTo(SettlementResult.Outcome.REQUIRES_REVIEW);
        assertClaimedRollback(fixture);
    }

    @Test
    void claimedLedgerFailureRollsBackOrderVoucherParticipantsLedgerAndWork() {
        ClaimedFixture fixture = claimedFixture();
        SettlementLedgerPort failAfterInsert = new SettlementLedgerPort() {
            @Override public Optional<SettlementReference> findForUpdateByOrderId(Integer orderId) {
                return realLedger.findForUpdateByOrderId(orderId);
            }
            @Override public SettlementReference insert(Integer orderId, Integer attemptId, Integer eventId,
                    Instant paidAt, Instant settledAt) {
                realLedger.insert(orderId, attemptId, eventId, paidAt, settledAt);
                throw new IllegalStateException("Injected failure after immutable ledger insert");
            }
        };

        SettlementResult result = claimedSettlement(participants, failAfterInsert, vouchers)
                .executeClaimedWork(fixture.claim());

        assertThat(result.outcome()).isEqualTo(SettlementResult.Outcome.REQUIRES_REVIEW);
        assertClaimedRollback(fixture);
    }

    @Test
    void transientClaimedFailureIsNotSuccessAndRetryMetadataFollowsFullRollback() {
        ClaimedFixture fixture = claimedFixture();
        participants.transientFailAt = "inventory-confirm";

        SettlementResult result = claimedSettlement(participants, realLedger, vouchers)
                .executeClaimedWork(fixture.claim());

        assertThat(result.outcome()).isEqualTo(SettlementResult.Outcome.RETRYABLE_FAILURE);
        assertNoClaimedBusinessEffects(fixture.fixture());
        assertThat(workState(fixture.workItemId())).isEqualTo("LEASED");
        assertThat(claimService.recordTransientFailure(fixture.claim(),
                SettlementWorkClaimService.TransientFailure.TRANSIENT_DATABASE))
                .isEqualTo(SettlementWorkClaimService.RetryResult.RETRY_SCHEDULED);
        assertThat(workState(fixture.workItemId())).isEqualTo("RETRY_WAIT");
    }

    @Test
    void staleTokenCannotSettleAndExpiredLeaseFailsFinalFenceWithFullRollback() {
        ClaimedFixture staleFixture = claimedFixture();
        var stale = new SettlementWorkClaimService.Claim(staleFixture.claim().id(),
                staleFixture.claim().paymentEventId(), UUID.randomUUID().toString(),
                staleFixture.claim().workerId(), staleFixture.claim().attemptCount(),
                staleFixture.claim().retryGeneration(), staleFixture.claim().claimedAt(), staleFixture.claim().leaseUntil());
        SettlementResult staleResult = claimedSettlement(participants, realLedger, vouchers).executeClaimedWork(stale);
        assertThat(staleResult.outcome()).isEqualTo(SettlementResult.Outcome.REQUIRES_REVIEW);
        assertNoClaimedBusinessEffects(staleFixture.fixture());
        assertThat(workState(staleFixture.workItemId())).isEqualTo("LEASED");

        ClaimedFixture expiredFixture = claimedFixture();
        jdbc.update("UPDATE payment_settlement_work_items SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
                expiredFixture.workItemId());
        SettlementResult expiredResult = claimedSettlement(participants, realLedger, vouchers)
                .executeClaimedWork(expiredFixture.claim());
        assertThat(expiredResult.outcome()).isEqualTo(SettlementResult.Outcome.REQUIRES_REVIEW);
        assertNoClaimedBusinessEffects(expiredFixture.fixture());
        assertThat(workState(expiredFixture.workItemId())).isEqualTo("LEASED");
    }

    @Test
    void competingClaimCannotTakeAnUnexpiredWorkItemWhileSettlementHoldsOrderLock() throws Exception {
        ClaimedFixture fixture = claimedFixture();
        CountDownLatch participantEntered = new CountDownLatch(1);
        CountDownLatch releaseParticipant = new CountDownLatch(1);
        participants.pauseAtInventory = participantEntered;
        participants.continueInventory = releaseParticipant;
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            SettlementCoordinator coordinator = claimedSettlement(participants, realLedger, vouchers);
            Future<SettlementResult> execution = pool.submit(() -> coordinator.executeClaimedWork(fixture.claim()));
            assertThat(participantEntered.await(8, TimeUnit.SECONDS)).isTrue();
            Future<Optional<SettlementWorkClaimService.Claim>> competitor = pool.submit(
                    () -> claimService.claimNext("mysql-competing-worker"));
            assertThat(competitor.get(8, TimeUnit.SECONDS)).isEmpty();
            Future<ExpirationResult> expiration = pool.submit(
                    () -> expiration(participants, Clock.fixed(DEADLINE, ZoneOffset.UTC)).expire(fixture.fixture().orderId()));
            assertThatThrownBy(() -> expiration.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseParticipant.countDown();
            assertThat(execution.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(SettlementResult.Outcome.SETTLED);
            assertThat(expiration.get(10, TimeUnit.SECONDS).outcome())
                    .isIn(ExpirationResult.Outcome.NOT_ELIGIBLE, ExpirationResult.Outcome.REQUIRES_REVIEW);
            assertThat(workState(fixture.workItemId())).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Long.class,
                    fixture.fixture().orderId())).isEqualTo(1L);
            assertThat(effectCount(fixture.fixture(), "course-release")).isZero();
            assertThat(effectCount(fixture.fixture(), "inventory-release")).isZero();
        } finally {
            releaseParticipant.countDown();
            shutdown(pool);
            participants.pauseAtInventory = null;
            participants.continueInventory = null;
        }
    }

    @Test
    void leaseExpiringDuringParticipantProcessingIsRejectedByFinalFence() throws Exception {
        ClaimedFixture fixture = claimedFixture();
        CountDownLatch participantEntered = new CountDownLatch(1);
        CountDownLatch releaseParticipant = new CountDownLatch(1);
        participants.pauseAtInventory = participantEntered;
        participants.continueInventory = releaseParticipant;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            SettlementCoordinator coordinator = claimedSettlement(participants, realLedger, vouchers);
            Future<SettlementResult> execution = pool.submit(() -> coordinator.executeClaimedWork(fixture.claim()));
            assertThat(participantEntered.await(8, TimeUnit.SECONDS)).isTrue();
            // The settlement transaction has not locked the work row yet; this independent autocommit update
            // deterministically lets the final database-clock fence observe an expired lease.
            jdbc.update("UPDATE payment_settlement_work_items SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
                    fixture.workItemId());
            releaseParticipant.countDown();
            assertThat(execution.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(SettlementResult.Outcome.REQUIRES_REVIEW);
            assertNoClaimedBusinessEffects(fixture.fixture());
            assertThat(workState(fixture.workItemId())).isEqualTo("LEASED");
        } finally {
            releaseParticipant.countDown();
            shutdown(pool);
            participants.pauseAtInventory = null;
            participants.continueInventory = null;
        }
    }

    @Test
    void concurrentExecutionOfOneClaimProducesOneSettlementAndNoDuplicateEffects() throws Exception {
        ClaimedFixture fixture = claimedFixture();
        CountDownLatch participantEntered = new CountDownLatch(1);
        CountDownLatch releaseParticipant = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        participants.pauseAtInventory = participantEntered;
        participants.continueInventory = releaseParticipant;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            SettlementCoordinator coordinator = claimedSettlement(participants, realLedger, vouchers);
            Future<SettlementResult> first = pool.submit(() -> coordinator.executeClaimedWork(fixture.claim()));
            assertThat(participantEntered.await(8, TimeUnit.SECONDS)).isTrue();
            Future<SettlementResult> second = pool.submit(() -> {
                secondStarted.countDown();
                return coordinator.executeClaimedWork(fixture.claim());
            });
            assertThat(secondStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseParticipant.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(SettlementResult.Outcome.SETTLED);
            assertThat(second.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(SettlementResult.Outcome.ALREADY_SETTLED);
            assertThat(workState(fixture.workItemId())).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Long.class,
                    fixture.fixture().orderId())).isEqualTo(1L);
            assertThat(effectCount(fixture.fixture(), "course-confirm")).isEqualTo(1L);
            assertThat(effectCount(fixture.fixture(), "inventory-confirm")).isEqualTo(1L);
        } finally {
            releaseParticipant.countDown();
            shutdown(pool);
            participants.pauseAtInventory = null;
            participants.continueInventory = null;
        }
    }

    @Test
    void differentSuccessfulEventForSameOrderCannotCreateSecondSettlement() {
        ClaimedFixture first = claimedFixture();
        SettlementCoordinator coordinator = claimedSettlement(participants, realLedger, vouchers);
        assertThat(coordinator.executeClaimedWork(first.claim()).outcome()).isEqualTo(SettlementResult.Outcome.SETTLED);

        ClaimedFixture second = additionalClaimedEvent(first.fixture());
        SettlementResult result = coordinator.executeClaimedWork(second.claim());

        assertThat(result.outcome()).isEqualTo(SettlementResult.Outcome.REQUIRES_REVIEW);
        assertThat(workState(second.workItemId())).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Long.class,
                first.fixture().orderId())).isEqualTo(1L);
        assertThat(voucherValue("redeemed_count", first.fixture().voucherId())).isEqualTo(1L);
        assertThat(effectCount(first.fixture(), "course-confirm")).isEqualTo(1L);
        assertThat(effectCount(first.fixture(), "inventory-confirm")).isEqualTo(1L);
    }

    @Test
    void deadlockVictimRollsBackItsWholeTransaction() throws Exception {
        ClaimedFixture settlementFixture = claimedFixture();
        Fixture otherOrder = fixture(PaymentStatus.FAILED, false);
        CountDownLatch settlementReadyToLockOtherOrder = new CountDownLatch(1);
        CountDownLatch allowSettlementLock = new CountDownLatch(1);
        CountDownLatch competitorOwnsOtherOrder = new CountDownLatch(1);
        CountDownLatch allowCompetitorLock = new CountDownLatch(1);
        AtomicReference<Throwable> competitorFailure = new AtomicReference<>();
        DeadlockParticipants invertedAdapter = new DeadlockParticipants(jdbc, otherOrder.orderId(),
                settlementReadyToLockOtherOrder, allowSettlementLock);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            SettlementCoordinator coordinator = claimedSettlement(invertedAdapter, realLedger, vouchers);
            Future<SettlementResult> settlement = pool.submit(() -> coordinator.executeClaimedWork(settlementFixture.claim()));
            assertThat(settlementReadyToLockOtherOrder.await(8, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> competitor = pool.submit(() -> {
                try {
                    tx().executeWithoutResult(status -> {
                        orders.findForUpdate(otherOrder.orderId(), otherOrder.userId()).orElseThrow();
                        jdbc.update("INSERT INTO payment_lifecycle_it_effects(order_id,kind,item_id) VALUES (?,?,?)",
                                otherOrder.orderId(), "deadlock-competitor", 1);
                        competitorOwnsOtherOrder.countDown();
                        await(allowCompetitorLock);
                        orders.findForUpdate(settlementFixture.fixture().orderId(),
                                settlementFixture.fixture().userId()).orElseThrow();
                    });
                    return true;
                } catch (org.springframework.dao.DataAccessException deadlockVictim) {
                    competitorFailure.set(deadlockVictim);
                    return false;
                }
            });
            assertThat(competitorOwnsOtherOrder.await(8, TimeUnit.SECONDS)).isTrue();
            allowSettlementLock.countDown();
            assertThatThrownBy(() -> settlement.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            allowCompetitorLock.countDown();

            SettlementResult settlementResult = settlement.get(12, TimeUnit.SECONDS);
            boolean competitorCommitted = competitor.get(12, TimeUnit.SECONDS);
            assertThat(competitorCommitted).isEqualTo(
                    settlementResult.outcome() == SettlementResult.Outcome.RETRYABLE_FAILURE);
            assertThat(competitorCommitted ? isMySqlDeadlock(invertedAdapter.failure)
                    : isMySqlDeadlock(competitorFailure.get())).isTrue();
            if (settlementResult.outcome() == SettlementResult.Outcome.SETTLED) {
                assertThat(workState(settlementFixture.workItemId())).isEqualTo("COMPLETED");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Long.class,
                        settlementFixture.fixture().orderId())).isEqualTo(1L);
            } else {
                assertThat(settlementResult.outcome()).isEqualTo(SettlementResult.Outcome.RETRYABLE_FAILURE);
                assertNoClaimedBusinessEffects(settlementFixture.fixture());
                assertThat(workState(settlementFixture.workItemId())).isEqualTo("LEASED");
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_lifecycle_it_effects WHERE order_id=? AND kind='deadlock-competitor'",
                    Long.class, otherOrder.orderId())).isEqualTo(competitorCommitted ? 1L : 0L);
        } finally {
            allowSettlementLock.countDown();
            allowCompetitorLock.countDown();
            shutdown(pool);
        }
    }

    @Test
    void transientDatabaseFailureIsRetryableOnlyAfterOwnedTransactionRollsBack() {
        Fixture fixture = fixture(PaymentStatus.FAILED, false);
        participants.transientFailAt = "inventory-release";
        ExpirationResult result = expiration(participants, Clock.fixed(DEADLINE, ZoneOffset.UTC)).expire(fixture.orderId());
        assertThat(result.outcome()).isEqualTo(ExpirationResult.Outcome.RETRYABLE_FAILURE);
        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertReservedVoucher(fixture);
        assertThat(effectCount(fixture, "course-release")).isZero();
        assertThat(effectCount(fixture, "inventory-release")).isZero();
    }

    private void assertExpirationWaitsForEvidenceCommit(Fixture fixture, boolean conflict) throws Exception {
        CountDownLatch writerOwnsOrder = new CountDownLatch(1);
        CountDownLatch commitWriter = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> writer = pool.submit(() -> tx().executeWithoutResult(status -> {
                OrderJpaEntity locked = orders.findForUpdate(fixture.orderId(), fixture.userId()).orElseThrow();
                PaymentAttemptJpaEntity attempt = attempts.findForUpdate(fixture.attemptId(), fixture.userId()).orElseThrow();
                PaymentPersistenceMapper mapper = new PaymentPersistenceMapper();
                if (conflict) {
                    PaymentEventJpaEntity firstRow = events.findById(fixture.eventId()).orElseThrow();
                    PaymentEventAudit first = mapper.toDomain(firstRow);
                    PaymentAttempt routed = mapper.toDomain(attempt);
                    PaymentEvent conflicting = new PaymentEvent("vnpay", first.event().eventReference(),
                            first.event().merchantReference(), "different-tx-" + fixture.orderId(),
                            fixture.amountVnd(), "VND", PaymentEvent.Outcome.SUCCEEDED, PAID_AT,
                            RECEIVED_AT, "b".repeat(64), "vnpay-hmac-sha512;paydate=valid");
                    em.persist(PaymentEventConflictJpaEntity.verifiedConflict(first, conflicting, routed,
                            OrderStatus.PENDING_PAYMENT));
                } else {
                    PaymentAttempt routed = mapper.toDomain(attempt);
                    PaymentEvent unknown = new PaymentEvent("vnpay", "expiry-race-" + UUID.randomUUID(),
                            routed.merchantReference(), "unknown-tx-" + fixture.orderId(), fixture.amountVnd(), "VND",
                            PaymentEvent.Outcome.UNKNOWN, PAID_AT, RECEIVED_AT, "c".repeat(64),
                            "vnpay-hmac-sha512;paydate=valid");
                    PaymentEventAudit audit = new PaymentEventAudit(null, fixture.attemptId(), fixture.orderId(),
                            fixture.userId(), unknown, unknown.fingerprint(), PaymentStatus.FAILED,
                            PaymentStatus.UNKNOWN, PaymentEventAudit.Disposition.APPLIED,
                            PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
                    events.saveAndFlush(PaymentEventJpaEntity.newSnapshot(audit));
                }
                em.flush();
                writerOwnsOrder.countDown();
                await(commitWriter);
            }));
            assertThat(writerOwnsOrder.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ExpirationResult> expiry = pool.submit(() -> expiration(participants,
                    Clock.fixed(DEADLINE, ZoneOffset.UTC)).expire(fixture.orderId()));
            // Bounded Future wait confirms expiry is blocked behind the writer's Order lock; no sleep ordering.
            assertThatThrownBy(() -> expiry.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commitWriter.countDown();
            writer.get(8, TimeUnit.SECONDS);
            assertThat(expiry.get(8, TimeUnit.SECONDS).outcome()).isEqualTo(ExpirationResult.Outcome.REQUIRES_REVIEW);
        } finally { commitWriter.countDown(); }
    }

    private Fixture fixture(PaymentStatus attemptStatus, boolean withEvent) {
        return fixture(attemptStatus, withEvent, withEvent ? PaymentEvent.Outcome.SUCCEEDED : null);
    }

    private Fixture fixture(PaymentStatus attemptStatus, boolean withEvent, PaymentEvent.Outcome outcome) {
        Fixture fixture = tx().execute(status -> {
            User user = users.saveAndFlush(User.builder().fullName("MySQL lifecycle IT")
                    .email("lifecycle-" + UUID.randomUUID() + "@example.test").passwordHash("test-only")
                    .role(Role.USER).isActive(true).build());
            String code = "life-" + UUID.randomUUID();
            Voucher voucher = new Voucher(code, OrderVoucher.Kind.FIXED_VND, VOUCHER_DISCOUNT,
                    OptionalLong.empty(), true, NOW.minusSeconds(60), DEADLINE.plusSeconds(3600), 0,
                    OptionalLong.of(100), OptionalLong.of(10));
            VoucherJpaEntity voucherRow = VoucherJpaEntity.fromDefinition(voucher);
            em.persist(voucherRow);
            em.flush();
            Order domain = Order.create(user.getId(), List.of(
                    new OrderLineSnapshot(OrderItemType.COURSE, COURSE_ID, "IT Course", 1, COURSE_PRICE, true),
                    new OrderLineSnapshot(OrderItemType.PRODUCT, PRODUCT_ID, "IT Product", 1, PRODUCT_PRICE, true)),
                    0, Optional.of(voucher.toOrderSnapshot()), Clock.fixed(NOW, ZoneOffset.UTC));
            OrderJpaEntity orderRow = OrderJpaEntity.fromSnapshot(domain);
            em.persist(orderRow);
            em.flush();
            StoredOrder stored = new OrderPersistenceMapper().toStored(orderRow);
            var quote = vouchers.quoteAndLock(user.getId(), code, COURSE_PRICE + PRODUCT_PRICE, NOW).orElseThrow();
            vouchers.reserveForOrder(user.getId(), stored, domain, quote, NOW);

            String merchant = "merchant-" + UUID.randomUUID();
            String transactionRef = attemptStatus == PaymentStatus.SUCCEEDED ? "txn-" + UUID.randomUUID() : null;
            Instant succeededAt = attemptStatus == PaymentStatus.SUCCEEDED ? PAID_AT : null;
            PaymentAttempt attempt = new PaymentAttempt(null, orderRow.getId(), user.getId(), "vnpay", merchant,
                    domain.getTotalVnd(), "VND", NOW, DEADLINE, NOW, RECEIVED_AT, attemptStatus,
                    transactionRef, succeededAt, PaymentAttempt.ReviewReason.NONE);
            PaymentAttemptJpaEntity attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
            em.persist(attemptRow);
            em.flush();
            Integer eventId = null;
            if (withEvent && outcome != null) {
                PaymentEvent event = new PaymentEvent("vnpay", merchant + ":" + transactionRef, merchant,
                        transactionRef, domain.getTotalVnd(), "VND", outcome,
                        outcome == PaymentEvent.Outcome.SUCCEEDED ? PAID_AT : NOW.plusSeconds(1), RECEIVED_AT,
                        "a".repeat(64), "vnpay-hmac-sha512;paydate=valid");
                PaymentStatus after = outcome == PaymentEvent.Outcome.SUCCEEDED ? PaymentStatus.SUCCEEDED
                        : outcome == PaymentEvent.Outcome.UNKNOWN ? PaymentStatus.UNKNOWN : PaymentStatus.FAILED;
                PaymentEventAudit audit = new PaymentEventAudit(null, attemptRow.getId(), orderRow.getId(),
                        user.getId(), event, event.fingerprint(), PaymentStatus.CREATED, after,
                        PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE,
                        OrderStatus.PENDING_PAYMENT);
                PaymentEventJpaEntity eventRow = PaymentEventJpaEntity.newSnapshot(audit);
                em.persist(eventRow);
                em.flush();
                eventId = eventRow.getId();
            }
            return new Fixture(user.getId(), voucherRow.getId(), orderRow.getId(), attemptRow.getId(), eventId,
                    domain.getTotalVnd(), code);
        });
        fixtures.add(fixture);
        return fixture;
    }

    private ClaimedFixture claimedFixture() {
        Fixture fixture = fixture(PaymentStatus.SUCCEEDED, true);
        PaymentSettlementWorkItemJpaEntity work = tx().execute(status -> workItems.saveAndFlush(
                PaymentSettlementWorkItemJpaEntity.ready(fixture.eventId(), NOW.minusSeconds(1))));
        SettlementWorkClaimService.Claim claim = claimService.claimNext("mysql-worker-" + UUID.randomUUID())
                .orElseThrow(() -> new AssertionError("Expected this fixture's READY work item to be claimed"));
        assertThat(claim.id()).isEqualTo(work.getId());
        return new ClaimedFixture(fixture, work.getId(), claim);
    }

    private ClaimedFixture additionalClaimedEvent(Fixture fixture) {
        Integer workId = tx().execute(status -> {
            OrderJpaEntity order = orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow();
            String transactionRef = "second-txn-" + UUID.randomUUID();
            PaymentAttempt attempt = new PaymentAttempt(null, order.getId(), fixture.userId(), "vnpay",
                    "second-merchant-" + UUID.randomUUID(), order.getTotalVnd(), "VND", order.getCreatedAt(),
                    order.getPaymentExpiresAt(), NOW.plusSeconds(3), RECEIVED_AT, PaymentStatus.SUCCEEDED,
                    transactionRef, PAID_AT, PaymentAttempt.ReviewReason.NONE);
            PaymentAttemptJpaEntity attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
            em.persist(attemptRow);
            em.flush();
            PaymentEvent event = new PaymentEvent("vnpay", attempt.merchantReference() + ":" + transactionRef,
                    attempt.merchantReference(), transactionRef, order.getTotalVnd(), "VND",
                    PaymentEvent.Outcome.SUCCEEDED, PAID_AT, RECEIVED_AT, "d".repeat(64),
                    "vnpay-hmac-sha512;paydate=valid");
            PaymentEventAudit audit = new PaymentEventAudit(null, attemptRow.getId(), order.getId(), fixture.userId(),
                    event, event.fingerprint(), PaymentStatus.CREATED, PaymentStatus.SUCCEEDED,
                    PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE,
                    OrderStatus.PENDING_PAYMENT);
            PaymentEventJpaEntity eventRow = PaymentEventJpaEntity.newSnapshot(audit);
            em.persist(eventRow);
            em.flush();
            return workItems.saveAndFlush(PaymentSettlementWorkItemJpaEntity.ready(eventRow.getId(), NOW.minusSeconds(1)))
                    .getId();
        });
        SettlementWorkClaimService.Claim claim = claimService.claimNext("mysql-second-worker-" + UUID.randomUUID())
                .orElseThrow();
        assertThat(claim.id()).isEqualTo(workId);
        return new ClaimedFixture(fixture, workId, claim);
    }

    private SettlementCoordinator settlement(TestParticipants parts, SettlementLedgerPort ledger) {
        return new SettlementCoordinator(orders, attempts, events, ledger, parts, parts, vouchers,
                Clock.fixed(RECEIVED_AT.plusSeconds(1), ZoneOffset.UTC), transactionManager);
    }

    private SettlementCoordinator claimedSettlement(TestParticipants parts, SettlementLedgerPort ledger,
            VoucherConsumptionPort voucherPort) {
        return new SettlementCoordinator(orders, attempts, events, ledger, parts, parts, voucherPort,
                Clock.fixed(RECEIVED_AT.plusSeconds(1), ZoneOffset.UTC), new SettlementTimeoutProperties(),
                transactionManager, workItems, workClaims);
    }

    private OrderExpirationCoordinator expiration(TestParticipants parts, Clock clock) {
        return new OrderExpirationCoordinator(orders, attempts, events, conflicts, settlements,
                vouchers, parts, parts, clock, transactionManager);
    }

    private long voucherValue(String column, Integer voucherId) {
        return jdbc.queryForObject("SELECT " + column + " FROM vouchers WHERE id=?", Long.class, voucherId);
    }
    private long userVoucherValue(String column, Fixture f) {
        return jdbc.queryForObject("SELECT " + column + " FROM voucher_user_usage WHERE voucher_id=? AND user_id=?", Long.class, f.voucherId(), f.userId());
    }
    private long effectCount(Fixture f, String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_lifecycle_it_effects WHERE order_id=? AND kind=?", Long.class, f.orderId(), kind);
    }
    private String workState(Integer workId) {
        return jdbc.queryForObject("SELECT state FROM payment_settlement_work_items WHERE id=?", String.class, workId);
    }
    private static boolean isMySqlDeadlock(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && (sql.getErrorCode() == 1213 || "40001".equals(sql.getSQLState()))) return true;
        }
        return false;
    }
    private void assertClaimedRollback(ClaimedFixture f) {
        assertNoClaimedBusinessEffects(f.fixture());
        assertThat(workState(f.workItemId())).isEqualTo("MANUAL_REVIEW");
    }
    private void assertNoClaimedBusinessEffects(Fixture f) {
        assertThat(orderStatus(f)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(settlements.findByOrderId(f.orderId())).isEmpty();
        assertReservedVoucher(f);
        assertThat(effectCount(f, "course-confirm")).isZero();
        assertThat(effectCount(f, "inventory-confirm")).isZero();
    }
    private OrderStatus orderStatus(Fixture f) {
        return OrderStatus.valueOf(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, f.orderId()));
    }
    private void assertReservedVoucher(Fixture f) {
        assertThat(voucherValue("reserved_count", f.voucherId())).isEqualTo(1L);
        assertThat(voucherValue("redeemed_count", f.voucherId())).isZero();
        assertThat(userVoucherValue("reserved_count", f)).isEqualTo(1L);
        assertThat(userVoucherValue("redeemed_count", f)).isZero();
    }
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("MySQL IT synchronization timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private static void shutdown(ExecutorService pool) {
        pool.shutdownNow();
        try { if (!pool.awaitTermination(3, TimeUnit.SECONDS)) throw new IllegalStateException("Worker threads did not stop"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private record Fixture(Integer userId, Integer voucherId, Integer orderId, Integer attemptId,
                           Integer eventId, long amountVnd, String voucherCode) {
        IpnReconciliationResult success() {
            return new IpnReconciliationResult(IpnClassification.VALID_ON_TIME_SUCCESS, attemptId, orderId,
                    eventId, null, PaymentEventAudit.Disposition.APPLIED,
                    PaymentAttempt.ReviewReason.NONE, false);
        }
    }

    private record ClaimedFixture(Fixture fixture, Integer workItemId, SettlementWorkClaimService.Claim claim) { }

    /** Test-only JDBC participant writes in the caller's transaction; not a production provider or schema adapter. */
    private static class TestParticipants implements InventoryReservationConfirmationPort,
            CourseOwnershipConfirmationPort, InventoryReservationReleasePort, CoursePurchaseReleasePort {
        protected final JdbcTemplate jdbc;
        private volatile String failAt;
        private volatile String transientFailAt;
        private volatile CountDownLatch pauseAtInventory;
        private volatile CountDownLatch continueInventory;
        private TestParticipants(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public void confirmProductForOrder(Integer userId, Integer productId, Integer orderId, Instant at) {
            effect("inventory-confirm", orderId, productId);
            CountDownLatch entered = pauseAtInventory;
            CountDownLatch release = continueInventory;
            if (entered != null && release != null) {
                entered.countDown();
                await(release);
            }
        }
        @Override public void confirmCourseForOrder(Integer userId, Integer courseId, Integer orderId,
                                                    Integer orderItemId, Instant at) { effect("course-confirm", orderId, courseId); }
        @Override public void releaseProductForExpiredOrder(Integer userId, Integer productId, Integer orderId, Instant at) { effect("inventory-release", orderId, productId); }
        @Override public void releaseCourseForExpiredOrder(Integer userId, Integer courseId, Integer orderId, Instant at) { effect("course-release", orderId, courseId); }
        private void effect(String kind, Integer orderId, Integer itemId) {
            jdbc.update("INSERT INTO payment_lifecycle_it_effects(order_id,kind,item_id) VALUES (?,?,?)", orderId, kind, itemId);
            if (kind.equals(transientFailAt)) throw new TestTransientFailure();
            if (kind.equals(failAt)) throw new IllegalStateException("Injected " + kind + " failure");
        }
    }
    private static final class TestTransientFailure extends TransientDataAccessException {
        private TestTransientFailure() { super("Injected retryable transaction participant failure"); }
    }

    private static final class DeadlockParticipants extends TestParticipants {
        private final Integer otherOrderId;
        private final CountDownLatch ready;
        private final CountDownLatch allowLock;
        private volatile Throwable failure;
        private DeadlockParticipants(JdbcTemplate jdbc, Integer otherOrderId, CountDownLatch ready,
                                     CountDownLatch allowLock) {
            super(jdbc); this.otherOrderId = otherOrderId; this.ready = ready; this.allowLock = allowLock;
        }
        @Override public void confirmProductForOrder(Integer userId, Integer productId, Integer orderId, Instant at) {
            super.confirmProductForOrder(userId, productId, orderId, at);
            ready.countDown();
            await(allowLock);
            try {
                jdbc.queryForObject("SELECT id FROM orders WHERE id=? FOR UPDATE", Integer.class, otherOrderId);
            } catch (RuntimeException deadlock) {
                failure = deadlock;
                throw deadlock;
            }
        }
    }
}
