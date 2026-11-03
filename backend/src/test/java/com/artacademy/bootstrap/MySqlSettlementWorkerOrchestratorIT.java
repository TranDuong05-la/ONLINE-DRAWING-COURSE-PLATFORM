package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.StoredOrder;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.settlement.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in worker orchestration recovery tests. TargetGuard rejects every target except the explicitly
 * acknowledged local drawing_course_it database before a datasource connection is opened.
 */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles({"test", "mysql-it"})
@ContextConfiguration(initializers = MySqlSchemaCompatibilityIT.TargetGuard.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2",
        "spring.datasource.hikari.maximum-pool-size=12"
})
class MySqlSettlementWorkerOrchestratorIT {
    private static final Instant NOW = Instant.parse("2026-10-30T02:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(1800);
    private static final Instant PAID_AT = NOW.plusSeconds(5);
    private static final Instant RECEIVED_AT = NOW.plusSeconds(6);
    private static final int COURSE_ID = 881;
    private static final int PRODUCT_ID = 882;
    private static final long COURSE_PRICE = 10_000L;
    private static final long PRODUCT_PRICE = 20_000L;
    private static final long VOUCHER_DISCOUNT = 1_000L;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentEventRepository events;
    @Autowired private PaymentSettlementRepository settlements;
    @Autowired private JpaSettlementLedgerAdapter ledger;
    @Autowired private JpaVoucherReservationService vouchers;
    @Autowired private PaymentSettlementWorkItemRepository workItems;
    @Autowired private PaymentSettlementWorkClaimRepository workClaims;
    @Autowired private SettlementWorkClaimService claimService;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Fixture> fixtures = new CopyOnWriteArrayList<>();
    private TestParticipants participants;

    @BeforeEach
    void verifyGuardedTargetAndCreateTestParticipantTable() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("drawing_course_it");
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualToIgnoringCase("REPEATABLE-READ");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history " +
                "WHERE success=TRUE AND version IN ('1','2','3','4','5','6','7','8','9')", Integer.class))
                .isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history " +
                "WHERE success=TRUE AND version='9'", Integer.class)).isEqualTo(1);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS payment_worker_it_effects (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    order_id INT NOT NULL,
                    kind VARCHAR(32) NOT NULL,
                    item_id INT NOT NULL,
                    UNIQUE KEY uk_payment_worker_it_effect (order_id,kind,item_id)
                ) ENGINE=InnoDB
                """);
        participants = new TestParticipants(jdbc);
    }

    @AfterEach
    void cleanOnlyOwnedFixtures() {
        for (Fixture fixture : fixtures) {
            Integer orderId = fixture.orderId();
            jdbc.update("DELETE FROM payment_settlement_work_actions WHERE work_item_id IN " +
                    "(SELECT id FROM payment_settlement_work_items WHERE payment_event_id IN " +
                    "(SELECT id FROM payment_events WHERE order_id=?))", orderId);
            jdbc.update("DELETE FROM payment_settlement_work_items WHERE payment_event_id IN " +
                    "(SELECT id FROM payment_events WHERE order_id=?)", orderId);
            jdbc.update("DELETE FROM payment_worker_it_effects WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_reconciliation_cases WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_event_conflicts WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_settlements WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_events WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_attempts WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM voucher_claims WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM order_items WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", orderId);
            jdbc.update("DELETE FROM voucher_user_usage WHERE voucher_id=? AND user_id=?",
                    fixture.voucherId(), fixture.userId());
            jdbc.update("DELETE FROM vouchers WHERE id=?", fixture.voucherId());
            jdbc.update("DELETE FROM users WHERE id=?", fixture.userId());
        }
        fixtures.clear();
    }

    @Test
    void twoOrchestratorsCompeteForOneReadyWorkItemExactlyOnce() throws Exception {
        WorkFixture fixture = readyFixture();
        SettlementCoordinator coordinator = coordinator(participants, transactionManager, workItems);
        SettlementWorkerOrchestrator first = new SettlementWorkerOrchestrator(claimService, coordinator);
        SettlementWorkerOrchestrator second = new SettlementWorkerOrchestrator(claimService, coordinator);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<SettlementWorkerOrchestrator.BatchSummary> a = pool.submit(() -> {
                await(start); return first.runBatch("mysql-orchestrator-a", 1);
            });
            Future<SettlementWorkerOrchestrator.BatchSummary> b = pool.submit(() -> {
                await(start); return second.runBatch("mysql-orchestrator-b", 1);
            });
            start.countDown();
            List<SettlementWorkerOrchestrator.BatchSummary> results =
                    List.of(a.get(12, TimeUnit.SECONDS), b.get(12, TimeUnit.SECONDS));
            assertThat(results.stream().mapToInt(SettlementWorkerOrchestrator.BatchSummary::claimed).sum())
                    .isEqualTo(1);
            assertThat(results.stream().mapToInt(SettlementWorkerOrchestrator.BatchSummary::completed).sum())
                    .isEqualTo(1);
        }

        assertSettledExactlyOnce(fixture);
    }

    @Test
    void twoOrchestratorsProcessMultipleReadyItemsWithoutDuplicateEffects() throws Exception {
        List<WorkFixture> work = List.of(readyFixture(), readyFixture(), readyFixture(), readyFixture());
        SettlementCoordinator coordinator = coordinator(participants, transactionManager, workItems);
        SettlementWorkerOrchestrator first = new SettlementWorkerOrchestrator(claimService, coordinator);
        SettlementWorkerOrchestrator second = new SettlementWorkerOrchestrator(claimService, coordinator);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<SettlementWorkerOrchestrator.BatchSummary> a = pool.submit(() -> {
                await(start); return first.runBatch("mysql-multi-a", work.size());
            });
            Future<SettlementWorkerOrchestrator.BatchSummary> b = pool.submit(() -> {
                await(start); return second.runBatch("mysql-multi-b", work.size());
            });
            start.countDown();
            List<SettlementWorkerOrchestrator.BatchSummary> results =
                    List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(results.stream().mapToInt(SettlementWorkerOrchestrator.BatchSummary::claimed).sum())
                    .isEqualTo(work.size());
            int initiallyCompleted = results.stream()
                    .mapToInt(SettlementWorkerOrchestrator.BatchSummary::completed).sum();
            int retryWait = results.stream()
                    .mapToInt(SettlementWorkerOrchestrator.BatchSummary::retryWait).sum();
            assertThat(results.stream().mapToInt(SettlementWorkerOrchestrator.BatchSummary::unknown).sum())
                    .isZero();
            assertThat(results.stream().mapToInt(SettlementWorkerOrchestrator.BatchSummary::manualReview).sum())
                    .isZero();
            assertThat(results.stream().mapToInt(SettlementWorkerOrchestrator.BatchSummary::closedNotEligible).sum())
                    .isZero();
            assertThat(initiallyCompleted + retryWait).isEqualTo(work.size());

            List<WorkFixture> recoverable = work.stream()
                    .filter(item -> "RETRY_WAIT".equals(workState(item)))
                    .toList();
            assertThat(recoverable).hasSize(retryWait);
            work.stream().filter(item -> "COMPLETED".equals(workState(item)))
                    .forEach(this::assertSettledExactlyOnce);
            recoverable.forEach(this::assertNoSettlementEffects);

            // A bounded concurrent pass need not drain the queue. Make only the observed retry rows due using
            // the database clock, then prove an explicit later invocation can recover every item without replay.
            for (WorkFixture item : recoverable) {
                assertThat(jdbc.update("""
                        UPDATE payment_settlement_work_items
                        SET available_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)
                        WHERE id=? AND state='RETRY_WAIT'
                        """, item.workItemId())).isEqualTo(1);
            }
            if (!recoverable.isEmpty()) {
                SettlementWorkerOrchestrator.BatchSummary drained =
                        first.runBatch("mysql-multi-drain", recoverable.size());
                assertThat(drained.claimed()).isEqualTo(recoverable.size());
                assertThat(drained.completed()).isEqualTo(recoverable.size());
                assertThat(drained.retryWait()).isZero();
                assertThat(drained.manualReview()).isZero();
                assertThat(drained.closedNotEligible()).isZero();
                assertThat(drained.unknown()).isZero();
            }
        }

        work.forEach(this::assertSettledExactlyOnce);
    }

    @Test
    void mixedBatchPersistsCompletedRetryReviewAndUnknownWhileContinuing() {
        WorkFixture completed = readyFixture();
        WorkFixture manual = readyFixture();
        WorkFixture unknown = readyFixture();
        WorkFixture retry = readyFixture();
        WorkFixture laterCompleted = readyFixture();
        jdbc.update("UPDATE orders SET status='EXPIRED',payment_status='EXPIRED' WHERE id=?",
                manual.fixture().orderId());
        participants.transientInventoryOrders.add(retry.fixture().orderId());

        SettlementCoordinator coordinator = coordinator(participants, transactionManager, workItems);
        RealClaimOperations claimOperations = new RealClaimOperations(claimService);
        SettlementWorkerOrchestrator orchestrator = new SettlementWorkerOrchestrator(claimOperations, claim -> {
            if (claim.paymentEventId() == unknown.fixture().eventId()) {
                throw new SettlementOutcomeUnknownException("Injected unresolved inspection",
                        new IllegalStateException("test-only unknown"));
            }
            return coordinator.executeClaimedWork(claim);
        });

        SettlementWorkerOrchestrator.BatchSummary result = orchestrator.runBatch("mysql-mixed", 5);

        assertThat(result.claimed()).isEqualTo(5);
        assertThat(result.completed()).isEqualTo(2);
        assertThat(result.retryWait()).isEqualTo(1);
        assertThat(result.manualReview()).isEqualTo(1);
        assertThat(result.closedNotEligible()).isZero();
        assertThat(result.unknown()).isEqualTo(1);
        assertThat(workState(completed)).isEqualTo("COMPLETED");
        assertThat(workState(manual)).isEqualTo("MANUAL_REVIEW");
        assertThat(workState(unknown)).isEqualTo("LEASED");
        assertThat(workState(retry)).isEqualTo("RETRY_WAIT");
        assertThat(workState(laterCompleted)).isEqualTo("COMPLETED");
        assertSettledExactlyOnce(completed);
        assertSettledExactlyOnce(laterCompleted);
        assertNoSettlementEffects(retry.fixture());
        assertNoSettlementEffects(unknown.fixture());
    }

    @Test
    void expiredLeaseTakeoverFencesOldWorkerAndNewWorkerSettlesOnce() throws Exception {
        WorkFixture fixture = readyFixture();
        PauseControl pause = participants.pauseOnce(fixture.fixture().orderId());
        SettlementCoordinator coordinator = coordinator(participants, transactionManager, workItems);
        RecordingClaimOperations claimsA = new RecordingClaimOperations(claimService);
        RecordingClaimOperations claimsB = new RecordingClaimOperations(claimService);
        SettlementWorkerOrchestrator workerA = new SettlementWorkerOrchestrator(claimsA,
                coordinator::executeClaimedWork);
        SettlementWorkerOrchestrator workerB = new SettlementWorkerOrchestrator(claimsB,
                coordinator::executeClaimedWork);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<SettlementWorkerOrchestrator.BatchSummary> a =
                    pool.submit(() -> workerA.runBatch("mysql-takeover-a", 1));
            assertThat(pause.entered().await(8, TimeUnit.SECONDS)).isTrue();
            SettlementWorkClaimService.Claim oldClaim = claimsA.awaitClaim();
            jdbc.update("UPDATE payment_settlement_work_items " +
                    "SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", fixture.workItemId());

            Future<SettlementWorkerOrchestrator.BatchSummary> b =
                    pool.submit(() -> workerB.runBatch("mysql-takeover-b", 1));
            SettlementWorkClaimService.Claim newClaim = claimsB.awaitClaim();
            assertThat(newClaim.id()).isEqualTo(oldClaim.id());
            assertThat(newClaim.leaseToken()).isNotEqualTo(oldClaim.leaseToken());
            assertThat(newClaim.attemptCount()).isEqualTo(2);

            pause.release().countDown();
            SettlementWorkerOrchestrator.BatchSummary oldResult = a.get(15, TimeUnit.SECONDS);
            SettlementWorkerOrchestrator.BatchSummary newResult = b.get(15, TimeUnit.SECONDS);
            assertThat(oldResult.unknown()).isEqualTo(1);
            assertThat(newResult.completed()).isEqualTo(1);
        } finally {
            pause.release().countDown();
        }

        assertThat(attemptCount(fixture)).isEqualTo(2);
        assertSettledExactlyOnce(fixture);
    }

    @Test
    void eighthAttemptTransitionsToManualReviewAndCannotBeClaimedNinthTime() {
        WorkFixture fixture = readyFixture();
        jdbc.update("UPDATE payment_settlement_work_items SET attempt_count=7 WHERE id=?", fixture.workItemId());
        participants.transientInventoryOrders.add(fixture.fixture().orderId());
        SettlementWorkerOrchestrator orchestrator = new SettlementWorkerOrchestrator(claimService,
                coordinator(participants, transactionManager, workItems));

        SettlementWorkerOrchestrator.BatchSummary result = orchestrator.runBatch("mysql-attempt-eight", 1);

        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.manualReview()).isEqualTo(1);
        assertThat(result.closedNotEligible()).isZero();
        assertThat(workState(fixture)).isEqualTo("MANUAL_REVIEW");
        assertThat(attemptCount(fixture)).isEqualTo(8);
        assertThat(claimService.claimNext("mysql-attempt-nine")).isEmpty();
        assertNoSettlementEffects(fixture.fixture());
    }

    @Test
    void lostCommitAcknowledgmentIsResolvedFromFreshMysqlTransaction() {
        WorkFixture fixture = readyFixture();
        PlatformTransactionManager uncertainManager = new CommitAcknowledgmentLossManager(transactionManager);
        SettlementCoordinator uncertainCoordinator = coordinator(participants, uncertainManager, workItems);
        SettlementWorkerOrchestrator orchestrator = new SettlementWorkerOrchestrator(claimService,
                uncertainCoordinator);

        SettlementWorkerOrchestrator.BatchSummary result = orchestrator.runBatch("mysql-commit-unknown", 1);

        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.items().getFirst().settlementOutcome())
                .isEqualTo(SettlementResult.Outcome.ALREADY_SETTLED);
        assertSettledExactlyOnce(fixture);
    }

    @Test
    void unresolvedFreshInspectionIsUnknownThenExpiredLeaseCanBeReclaimedSafely() {
        WorkFixture fixture = readyFixture();
        participants.failInventoryOrders.add(fixture.fixture().orderId());
        FailSecondReadWorkRepository failingReads = new FailSecondReadWorkRepository(workItems);
        SettlementCoordinator uncertainCoordinator = coordinator(participants, transactionManager, failingReads);
        SettlementWorkerOrchestrator uncertainWorker = new SettlementWorkerOrchestrator(claimService,
                uncertainCoordinator);

        SettlementWorkerOrchestrator.BatchSummary unknown = uncertainWorker.runBatch("mysql-unknown-a", 1);

        assertThat(unknown.unknown()).isEqualTo(1);
        assertThat(workState(fixture)).isEqualTo("LEASED");
        assertThat(attemptCount(fixture)).isEqualTo(1);
        assertNoSettlementEffects(fixture.fixture());

        participants.failInventoryOrders.clear();
        jdbc.update("UPDATE payment_settlement_work_items " +
                "SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", fixture.workItemId());
        SettlementWorkerOrchestrator recovery = new SettlementWorkerOrchestrator(claimService,
                coordinator(participants, transactionManager, workItems));
        SettlementWorkerOrchestrator.BatchSummary recovered = recovery.runBatch("mysql-unknown-b", 1);

        assertThat(recovered.completed()).isEqualTo(1);
        assertThat(attemptCount(fixture)).isEqualTo(2);
        assertSettledExactlyOnce(fixture);
    }

    @Test
    void completionFenceSamplesDatabaseTimeAfterWorkRowLockWait() throws Exception {
        WorkFixture fixture = readyFixture();
        SettlementWorkClaimService.Claim claim = claimService.claimNext("mysql-fence-old").orElseThrow();
        jdbc.update("UPDATE payment_settlement_work_items " +
                "SET lease_until=UTC_TIMESTAMP(6)+INTERVAL 2 SECOND WHERE id=?", fixture.workItemId());
        Instant expiresAt = jdbc.queryForObject("SELECT lease_until FROM payment_settlement_work_items WHERE id=?",
                LocalDateTime.class, fixture.workItemId()).toInstant(ZoneOffset.UTC);
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseRow = new CountDownLatch(1);
        FixedClaimOperations fixed = new FixedClaimOperations(claim, claimService);
        SettlementWorkerOrchestrator orchestrator = new SettlementWorkerOrchestrator(fixed,
                coordinator(participants, transactionManager, workItems)::executeClaimedWork);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM payment_settlement_work_items WHERE id=? FOR UPDATE",
                        Integer.class, fixture.workItemId());
                rowLocked.countDown();
                await(releaseRow);
            }));
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<SettlementWorkerOrchestrator.BatchSummary> execution =
                    pool.submit(() -> orchestrator.runBatch("mysql-fence-run", 1));
            waitUntilDatabaseTime(expiresAt);
            releaseRow.countDown();
            holder.get(8, TimeUnit.SECONDS);
            SettlementWorkerOrchestrator.BatchSummary result = execution.get(12, TimeUnit.SECONDS);
            assertThat(result.unknown()).isEqualTo(1);
        } finally {
            releaseRow.countDown();
        }

        assertThat(workState(fixture)).isEqualTo("LEASED");
        assertNoSettlementEffects(fixture.fixture());
    }

    @Test
    void orderLockTimeoutRollsBackBeforeAuthorizedRetryTransition() throws Exception {
        WorkFixture fixture = readyFixture();
        CountDownLatch orderLocked = new CountDownLatch(1);
        CountDownLatch releaseOrder = new CountDownLatch(1);
        CountDownLatch freshInspectionStarted = new CountDownLatch(1);
        CountDownLatch allowFreshInspection = new CountDownLatch(1);
        SignalingSecondReadWorkRepository signalingReads = new SignalingSecondReadWorkRepository(
                workItems, freshInspectionStarted, allowFreshInspection);
        SettlementCoordinator coordinator = coordinator(participants, transactionManager, signalingReads);
        SettlementWorkerOrchestrator orchestrator = new SettlementWorkerOrchestrator(claimService, coordinator);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(status -> {
                orders.findForUpdate(fixture.fixture().orderId(), fixture.fixture().userId()).orElseThrow();
                orderLocked.countDown();
                await(releaseOrder);
            }));
            assertThat(orderLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<SettlementWorkerOrchestrator.BatchSummary> execution =
                    pool.submit(() -> orchestrator.runBatch("mysql-lock-timeout", 1));
            assertThat(freshInspectionStarted.await(8, TimeUnit.SECONDS)).isTrue();
            releaseOrder.countDown();
            holder.get(8, TimeUnit.SECONDS);
            allowFreshInspection.countDown();

            SettlementWorkerOrchestrator.BatchSummary result = execution.get(12, TimeUnit.SECONDS);
            assertThat(result.retryWait()).isEqualTo(1);
        } finally {
            releaseOrder.countDown();
            allowFreshInspection.countDown();
        }

        assertThat(workState(fixture)).isEqualTo("RETRY_WAIT");
        assertThat(lastFailureCode(fixture)).isEqualTo("TRANSIENT_DATABASE");
        assertNoSettlementEffects(fixture.fixture());
    }

    private WorkFixture readyFixture() {
        Fixture fixture = fixture();
        Integer workId = tx().execute(status -> workItems.saveAndFlush(PaymentSettlementWorkItemJpaEntity.ready(
                fixture.eventId(), workClaims.databaseUtcNow().minusSeconds(1))).getId());
        return new WorkFixture(fixture, workId);
    }

    private Fixture fixture() {
        Fixture fixture = tx().execute(status -> {
            User user = users.saveAndFlush(User.builder().fullName("MySQL worker IT")
                    .email("worker-" + UUID.randomUUID() + "@example.test")
                    .passwordHash("test-only").role(Role.USER).isActive(true).build());
            Voucher voucher = new Voucher("worker-" + UUID.randomUUID(), OrderVoucher.Kind.FIXED_VND,
                    VOUCHER_DISCOUNT, OptionalLong.empty(), true, NOW.minusSeconds(60),
                    DEADLINE.plusSeconds(3600), 0, OptionalLong.of(100), OptionalLong.of(10));
            VoucherJpaEntity voucherRow = VoucherJpaEntity.fromDefinition(voucher);
            entityManager.persist(voucherRow);
            entityManager.flush();

            Order domain = Order.create(user.getId(), List.of(
                    new OrderLineSnapshot(OrderItemType.COURSE, COURSE_ID, "Worker IT Course", 1,
                            COURSE_PRICE, true),
                    new OrderLineSnapshot(OrderItemType.PRODUCT, PRODUCT_ID, "Worker IT Product", 1,
                            PRODUCT_PRICE, true)), 0, Optional.of(voucher.toOrderSnapshot()),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            OrderJpaEntity order = OrderJpaEntity.fromSnapshot(domain);
            entityManager.persist(order);
            entityManager.flush();
            StoredOrder stored = new OrderPersistenceMapper().toStored(order);
            var quote = vouchers.quoteAndLock(user.getId(), voucher.code(), COURSE_PRICE + PRODUCT_PRICE, NOW)
                    .orElseThrow();
            vouchers.reserveForOrder(user.getId(), stored, domain, quote, NOW);

            String merchant = "worker-merchant-" + UUID.randomUUID();
            String transactionReference = "worker-transaction-" + UUID.randomUUID();
            PaymentAttempt attempt = new PaymentAttempt(null, order.getId(), user.getId(), "vnpay", merchant,
                    domain.getTotalVnd(), "VND", NOW, DEADLINE, NOW, RECEIVED_AT, PaymentStatus.SUCCEEDED,
                    transactionReference, PAID_AT, PaymentAttempt.ReviewReason.NONE);
            PaymentAttemptJpaEntity attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
            entityManager.persist(attemptRow);
            entityManager.flush();

            PaymentEvent event = new PaymentEvent("vnpay", merchant + ":" + transactionReference, merchant,
                    transactionReference, domain.getTotalVnd(), "VND", PaymentEvent.Outcome.SUCCEEDED,
                    PAID_AT, RECEIVED_AT, "e".repeat(64), "vnpay-hmac-sha512;paydate=valid");
            PaymentEventAudit audit = new PaymentEventAudit(null, attemptRow.getId(), order.getId(), user.getId(),
                    event, event.fingerprint(), PaymentStatus.CREATED, PaymentStatus.SUCCEEDED,
                    PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE,
                    OrderStatus.PENDING_PAYMENT);
            PaymentEventJpaEntity eventRow = PaymentEventJpaEntity.newSnapshot(audit);
            entityManager.persist(eventRow);
            entityManager.flush();
            return new Fixture(user.getId(), voucherRow.getId(), order.getId(), attemptRow.getId(),
                    eventRow.getId());
        });
        fixtures.add(fixture);
        return fixture;
    }

    private SettlementCoordinator coordinator(TestParticipants participant,
                                              PlatformTransactionManager manager,
                                              PaymentSettlementWorkItemRepository workRepository) {
        return new SettlementCoordinator(orders, attempts, events, ledger, participant, participant, vouchers,
                Clock.fixed(RECEIVED_AT.plusSeconds(1), ZoneOffset.UTC), new SettlementTimeoutProperties(),
                manager, workRepository, workClaims);
    }

    private void assertSettledExactlyOnce(WorkFixture fixture) {
        assertThat(workState(fixture)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?",
                Long.class, fixture.fixture().orderId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE payment_event_id=?",
                Long.class, fixture.fixture().eventId())).isEqualTo(1L);
        assertThat(orderStatus(fixture.fixture())).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(voucherValue("reserved_count", fixture.fixture().voucherId())).isZero();
        assertThat(voucherValue("redeemed_count", fixture.fixture().voucherId())).isEqualTo(1L);
        assertThat(userVoucherValue("reserved_count", fixture.fixture())).isZero();
        assertThat(userVoucherValue("redeemed_count", fixture.fixture())).isEqualTo(1L);
        assertThat(effectCount(fixture.fixture(), "course-confirm")).isEqualTo(1L);
        assertThat(effectCount(fixture.fixture(), "inventory-confirm")).isEqualTo(1L);
    }

    private void assertNoSettlementEffects(WorkFixture fixture) {
        assertThat(workState(fixture)).isEqualTo("RETRY_WAIT");
        assertNoSettlementEffects(fixture.fixture());
    }

    private void assertNoSettlementEffects(Fixture fixture) {
        assertThat(orderStatus(fixture)).isNotEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?",
                Long.class, fixture.orderId())).isZero();
        assertThat(voucherValue("redeemed_count", fixture.voucherId())).isZero();
        assertThat(effectCount(fixture, "course-confirm")).isZero();
        assertThat(effectCount(fixture, "inventory-confirm")).isZero();
    }

    private String workState(WorkFixture fixture) {
        return jdbc.queryForObject("SELECT state FROM payment_settlement_work_items WHERE id=?",
                String.class, fixture.workItemId());
    }

    private int attemptCount(WorkFixture fixture) {
        return jdbc.queryForObject("SELECT attempt_count FROM payment_settlement_work_items WHERE id=?",
                Integer.class, fixture.workItemId());
    }

    private String lastFailureCode(WorkFixture fixture) {
        return jdbc.queryForObject("SELECT last_failure_code FROM payment_settlement_work_items WHERE id=?",
                String.class, fixture.workItemId());
    }

    private OrderStatus orderStatus(Fixture fixture) {
        return OrderStatus.valueOf(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",
                String.class, fixture.orderId()));
    }

    private long voucherValue(String column, Integer voucherId) {
        return jdbc.queryForObject("SELECT " + column + " FROM vouchers WHERE id=?", Long.class, voucherId);
    }

    private long userVoucherValue(String column, Fixture fixture) {
        return jdbc.queryForObject("SELECT " + column + " FROM voucher_user_usage " +
                "WHERE voucher_id=? AND user_id=?", Long.class, fixture.voucherId(), fixture.userId());
    }

    private long effectCount(Fixture fixture, String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_worker_it_effects " +
                "WHERE order_id=? AND kind=?", Long.class, fixture.orderId(), kind);
    }

    private void waitUntilDatabaseTime(Instant deadline) {
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (workClaims.databaseUtcNow().isBefore(deadline)) {
            if (System.nanoTime() >= timeout) throw new AssertionError("Database clock did not reach lease deadline");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("MySQL IT latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private record Fixture(Integer userId, Integer voucherId, Integer orderId, Integer attemptId,
                           Integer eventId) { }

    private record WorkFixture(Fixture fixture, Integer workItemId) { }

    private record PauseControl(CountDownLatch entered, CountDownLatch release, AtomicBoolean first) { }

    /** Test-only local participant; all writes join the settlement transaction. */
    private static final class TestParticipants implements InventoryReservationConfirmationPort,
            CourseOwnershipConfirmationPort {
        private final JdbcTemplate jdbc;
        private final Set<Integer> transientInventoryOrders = ConcurrentHashMap.newKeySet();
        private final Set<Integer> failInventoryOrders = ConcurrentHashMap.newKeySet();
        private final Map<Integer, PauseControl> pauses = new ConcurrentHashMap<>();

        private TestParticipants(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private PauseControl pauseOnce(Integer orderId) {
            PauseControl control = new PauseControl(new CountDownLatch(1), new CountDownLatch(1),
                    new AtomicBoolean());
            pauses.put(orderId, control);
            return control;
        }

        @Override
        public void confirmProductForOrder(Integer userId, Integer productId, Integer orderId, Instant confirmedAt) {
            effect("inventory-confirm", orderId, productId);
            PauseControl pause = pauses.get(orderId);
            if (pause != null && pause.first().compareAndSet(false, true)) {
                pause.entered().countDown();
                await(pause.release());
            }
            if (transientInventoryOrders.contains(orderId)) throw new TestTransientFailure();
            if (failInventoryOrders.contains(orderId)) throw new IllegalStateException("Injected participant failure");
        }

        @Override
        public void confirmCourseForOrder(Integer userId, Integer courseId, Integer orderId,
                                          Integer orderItemId, Instant confirmedAt) {
            effect("course-confirm", orderId, courseId);
        }

        private void effect(String kind, Integer orderId, Integer itemId) {
            jdbc.update("INSERT INTO payment_worker_it_effects(order_id,kind,item_id) VALUES (?,?,?)",
                    orderId, kind, itemId);
        }
    }

    private static final class TestTransientFailure extends TransientDataAccessException {
        private TestTransientFailure() {
            super("Injected transient worker participant failure");
        }
    }

    private static class RealClaimOperations implements SettlementWorkerOrchestrator.ClaimOperations {
        protected final SettlementWorkClaimService delegate;

        private RealClaimOperations(SettlementWorkClaimService delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<SettlementWorkClaimService.Claim> claimNext(String workerId) {
            return delegate.claimNext(workerId);
        }

        @Override
        public SettlementWorkClaimService.RetryResult recordTransientFailure(
                SettlementWorkClaimService.Claim claim,
                SettlementWorkClaimService.TransientFailure failure) {
            return delegate.recordTransientFailure(claim, failure);
        }
    }

    private static final class RecordingClaimOperations extends RealClaimOperations {
        private final CountDownLatch claimed = new CountDownLatch(1);
        private volatile SettlementWorkClaimService.Claim observed;

        private RecordingClaimOperations(SettlementWorkClaimService delegate) {
            super(delegate);
        }

        @Override
        public Optional<SettlementWorkClaimService.Claim> claimNext(String workerId) {
            Optional<SettlementWorkClaimService.Claim> result = super.claimNext(workerId);
            result.ifPresent(value -> {
                observed = value;
                claimed.countDown();
            });
            return result;
        }

        private SettlementWorkClaimService.Claim awaitClaim() {
            await(claimed);
            return observed;
        }
    }

    private static final class FixedClaimOperations extends RealClaimOperations {
        private final SettlementWorkClaimService.Claim fixed;
        private final AtomicBoolean delivered = new AtomicBoolean();

        private FixedClaimOperations(SettlementWorkClaimService.Claim fixed, SettlementWorkClaimService delegate) {
            super(delegate);
            this.fixed = fixed;
        }

        @Override
        public Optional<SettlementWorkClaimService.Claim> claimNext(String workerId) {
            return delivered.compareAndSet(false, true) ? Optional.of(fixed) : Optional.empty();
        }
    }

    private static class DelegatingWorkRepository implements PaymentSettlementWorkItemRepository {
        protected final PaymentSettlementWorkItemRepository delegate;

        private DelegatingWorkRepository(PaymentSettlementWorkItemRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<PaymentSettlementWorkItemJpaEntity> findById(Integer id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<PaymentSettlementWorkItemJpaEntity> findByPaymentEventId(Integer eventId) {
            return delegate.findByPaymentEventId(eventId);
        }

        @Override
        public <S extends PaymentSettlementWorkItemJpaEntity> S saveAndFlush(S workItem) {
            return delegate.saveAndFlush(workItem);
        }
    }

    private static final class FailSecondReadWorkRepository extends DelegatingWorkRepository {
        private final AtomicInteger reads = new AtomicInteger();

        private FailSecondReadWorkRepository(PaymentSettlementWorkItemRepository delegate) {
            super(delegate);
        }

        @Override
        public Optional<PaymentSettlementWorkItemJpaEntity> findById(Integer id) {
            if (reads.incrementAndGet() == 2) {
                throw new TransientDataAccessResourceException("Injected fresh-inspection failure");
            }
            return super.findById(id);
        }
    }

    private static final class SignalingSecondReadWorkRepository extends DelegatingWorkRepository {
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch secondRead;
        private final CountDownLatch allowSecondRead;

        private SignalingSecondReadWorkRepository(PaymentSettlementWorkItemRepository delegate,
                                                  CountDownLatch secondRead,
                                                  CountDownLatch allowSecondRead) {
            super(delegate);
            this.secondRead = secondRead;
            this.allowSecondRead = allowSecondRead;
        }

        @Override
        public Optional<PaymentSettlementWorkItemJpaEntity> findById(Integer id) {
            if (reads.incrementAndGet() == 2) {
                secondRead.countDown();
                await(allowSecondRead);
            }
            return super.findById(id);
        }
    }

    private static final class CommitAcknowledgmentLossManager implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private final AtomicBoolean loseNext = new AtomicBoolean(true);

        private CommitAcknowledgmentLossManager(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            delegate.commit(status);
            if (loseNext.compareAndSet(true, false)) {
                throw new TransientDataAccessResourceException("Injected post-commit acknowledgment loss");
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }
}
