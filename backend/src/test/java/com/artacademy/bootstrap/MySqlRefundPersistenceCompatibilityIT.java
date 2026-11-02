package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.refund.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles({"test", "mysql-refund-v10-it"})
@ContextConfiguration(initializers = RefundV10MySqlTargetGuard.class)
@Transactional
class MySqlRefundPersistenceCompatibilityIT {
    private static final Instant NOW = Instant.parse("2026-11-01T01:02:03.123456Z");
    private static final String A = "a".repeat(64), B = "b".repeat(64), C = "c".repeat(64), D = "d".repeat(64);

    @Autowired JdbcTemplate jdbc;
    @Autowired ConfigurableEnvironment environment;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserRepository users;
    @Autowired PaymentSettlementRepository settlements;
    @Autowired RefundRequestRepository requests;
    @Autowired RefundUnitAllocationRepository units;
    @Autowired RefundProviderAttemptRepository providerAttempts;
    @Autowired RefundReviewCaseRepository reviews;
    @Autowired RefundPersistenceService persistence;
    @Autowired RefundAuditedRequestService auditedRequests;
    @Autowired RefundAuditedEventService auditedEvents;

    @BeforeEach
    void requireExpectedMigratedTarget() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo(RefundV10MySqlTargetGuard.EXPECTED_DATABASE);
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.0.");
        assertThat(environment.getProperty("spring.flyway.target")).isEqualTo("10");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE", Integer.class))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='10' AND success=TRUE", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void v10SchemaCompositeRoutesCollationsAndDatetimePrecisionMatchMappings() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name IN ('refund_requests','refund_unit_allocations','refund_provider_attempts','refund_events','refund_review_cases')", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() "
                + "AND constraint_name IN ('uk_orders_id_user','uk_order_items_id_order','uk_payment_settlements_id_order') AND constraint_type='UNIQUE'", Integer.class)).isEqualTo(3);
        assertForeignKeyRoute("refund_requests", "fk_refund_request_owner_route", "order_id>id,user_id>user_id");
        assertForeignKeyRoute("refund_requests", "fk_refund_request_item_route", "order_item_id>id,order_id>order_id");
        assertForeignKeyRoute("refund_requests", "fk_refund_request_settlement_route", "payment_settlement_id>id,order_id>order_id");
        assertThat(column("refund_requests", "request_fingerprint", "COLLATION_NAME")).isEqualTo("ascii_bin");
        assertThat(column("refund_requests", "idempotency_key", "COLLATION_NAME")).isEqualTo("utf8mb4_bin");
        assertThat(column("refund_requests", "requested_at", "DATETIME_PRECISION")).isEqualTo("6");
        assertThat(column("refund_provider_attempts", "evidence_observed_at", "DATETIME_PRECISION")).isEqualTo("6");
    }

    @Test
    void compositeRoutesRollbackAndCapturedCapsAreEnforced() {
        Fixture fixture = fixture(OrderItemType.PRODUCT, 1, 100);
        var created = auditedRequests.create(command(fixture, "route", A, 1));
        assertThatThrownBy(() -> jdbc.update("UPDATE refund_requests SET order_id=? WHERE id=?",
                fixture.orderId + 1_000_000, created.request().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        Fixture cap = fixture(OrderItemType.PRODUCT, 2, 100);
        auditedRequests.create(command(cap, "cap-all", B, 2));
        assertThatThrownBy(() -> auditedRequests.create(command(cap, "cap-over", C, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT SUM(requested_amount_vnd) FROM refund_requests WHERE order_id=?",
                Long.class, cap.orderId)).isEqualTo(200L);
    }

    @Test
    void nullableMarkerPreservesReleasedHistoryAndProductAllocationIsDeterministic() {
        Fixture fixture = fixture(OrderItemType.PRODUCT, 3, 101);
        var first = auditedRequests.create(command(fixture, "units-1", A, 2));
        assertThat(first.allocations()).extracting(RefundUnitAllocationJpaEntity::getUnitPosition)
                .containsExactly(0, 1);
        assertThat(first.allocations()).extracting(RefundUnitAllocationJpaEntity::getAllocatedAmountVnd)
                .containsExactly(101L, 101L);
        units.findById(first.allocations().getFirst().getId()).orElseThrow().release(NOW.plusSeconds(1));
        jdbc.update("UPDATE refund_requests SET amount_reservation_status='RELEASED' WHERE id=?", first.request().getId());
        var second = auditedRequests.create(command(fixture, "units-2", B, 1));
        assertThat(second.allocations().getFirst().getUnitPosition()).isZero();
        assertThat(second.allocations().getFirst().getId()).isNotEqualTo(first.allocations().getFirst().getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_unit_allocations WHERE order_item_id=? AND unit_position=0",
                Long.class, fixture.itemId)).isEqualTo(2L);
    }

    @Test
    void unknownRetainsReservationProviderSlotAndMicrosecondEvidence() {
        Fixture fixture = fixture(OrderItemType.PRODUCT, 1, 100);
        var request = approved(auditedRequests.create(command(fixture, "unknown", A, 1)).request());
        var attempt = persistence.prepareProviderAttempt(request.getId(), fixture.userId, "vnpay",
                "mysql-refund-" + UUID.randomUUID(), NOW);
        persistence.startProviderAttempt(request.getId(), fixture.userId, attempt.getId(), NOW.plusSeconds(1));
        Instant observed = NOW.plusSeconds(2).plusNanos(789);
        persistence.recordUnknown(request.getId(), fixture.userId, attempt.getId(), B, "timeout", observed);
        assertThat(requests.findById(request.getId()).orElseThrow().getAmountReservationStatus())
                .isEqualTo(RefundPersistenceTypes.AmountReservationStatus.RESERVED);
        assertThat(providerAttempts.findById(attempt.getId()).orElseThrow().getExecutionSlot()).isEqualTo((byte) 1);
        em.flush();
        LocalDateTime stored = jdbc.queryForObject("SELECT evidence_observed_at FROM refund_provider_attempts WHERE id=?",
                LocalDateTime.class, attempt.getId());
        assertThat(stored).isEqualTo(LocalDateTime.ofInstant(observed, ZoneOffset.UTC).withNano(123456000));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void eventReplayConflictReviewPersistsAndRollbackIsAtomic() {
        Fixture fixture = committedFixture(OrderItemType.COURSE, 1, 100);
        try {
            var request = auditedRequests.create(command(fixture, "event", A, 1)).request();
            var original = eventCommand(request.getId(), fixture.userId, B);
            assertThat(auditedEvents.record(original).idempotentReplay()).isFalse();
            assertThat(auditedEvents.record(original).idempotentReplay()).isTrue();
            var changed = new RefundEventStoreService.EventCommand(request.getId(), fixture.userId, null,
                    "ADMIN_APPROVAL", "approval-1", C, A, D, null, NOW, NOW.plusSeconds(1));
            var conflict = catchThrowableOfType(() -> auditedEvents.record(changed),
                    RefundAuditedEventService.DurableEventConflict.class);
            assertThat(conflict).isNotNull();
            String caseType = tx().execute(status ->
                    reviews.findById(conflict.reviewCaseId()).orElseThrow().getCaseType());
            assertThat(caseType).isEqualTo("EVENT_IDENTITY_CONFLICT");

            long before = countRequests(fixture.orderId);
            assertThatThrownBy(() -> auditedRequests.create(command(fixture, "invalid-quantity", D, 2)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(countRequests(fixture.orderId)).isEqualTo(before);
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentUnitAndProviderSlotCompetitionEachProduceOneWinner() throws Exception {
        Fixture unitFixture = null;
        Fixture providerFixture = null;
        ExecutorService pool = null;
        List<Future<Boolean>> workers = new ArrayList<>();
        boolean workersStopped = true;
        try {
            unitFixture = committedFixture(OrderItemType.PRODUCT, 1, 100);
            providerFixture = committedFixture(OrderItemType.PRODUCT, 1, 100);
            pool = Executors.newFixedThreadPool(2);
            Fixture unitFixtureForWorkers = unitFixture;
            CountDownLatch start = new CountDownLatch(1);
            workers.add(pool.submit(() -> { await(start); return attemptCreate(command(unitFixtureForWorkers, "race-a", A, 1)); }));
            workers.add(pool.submit(() -> { await(start); return attemptCreate(command(unitFixtureForWorkers, "race-b", B, 1)); }));
            start.countDown();
            assertThat(successCount(workers)).isEqualTo(1);
            workers.clear();

            var request = approved(auditedRequests.create(command(providerFixture, "provider", C, 1)).request());
            Fixture providerFixtureForWorkers = providerFixture;
            CountDownLatch providerStart = new CountDownLatch(1);
            workers.add(pool.submit(() -> { await(providerStart); return attemptProvider(request.getId(), providerFixtureForWorkers.userId); }));
            workers.add(pool.submit(() -> { await(providerStart); return attemptProvider(request.getId(), providerFixtureForWorkers.userId); }));
            providerStart.countDown();
            assertThat(successCount(workers)).isEqualTo(1);
            Integer activeSlots = tx().execute(status -> jdbc.queryForObject(
                    "SELECT COUNT(*) FROM refund_provider_attempts WHERE refund_request_id=? AND execution_slot=1",
                    Integer.class, request.getId()));
            assertThat(activeSlots).isEqualTo(1);
        } finally {
            if (pool != null) workersStopped = stopWorkers(pool, workers);
            if (workersStopped) {
                try {
                    if (providerFixture != null) cleanup(providerFixture);
                } finally {
                    if (unitFixture != null) cleanup(unitFixture);
                }
            }
        }
        assertThat(workersStopped).as("workers must terminate before fixture cleanup").isTrue();
    }

    private void assertForeignKeyRoute(String table, String constraint, String expected) {
        String actual = jdbc.queryForObject("SELECT GROUP_CONCAT(CONCAT(column_name,'>',referenced_column_name) "
                + "ORDER BY ordinal_position SEPARATOR ',') FROM information_schema.key_column_usage "
                + "WHERE table_schema=DATABASE() AND table_name=? AND constraint_name=? AND referenced_table_name IS NOT NULL",
                String.class, table, constraint);
        assertThat(actual).isEqualTo(expected);
    }

    private String column(String table, String name, String property) {
        return jdbc.queryForObject("SELECT " + property + " FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", String.class, table, name);
    }

    private int successCount(List<Future<Boolean>> workers) throws Exception {
        int count = 0;
        for (Future<Boolean> worker : workers) if (worker.get(15, TimeUnit.SECONDS)) count++;
        return count;
    }

    private boolean stopWorkers(ExecutorService pool, List<? extends Future<?>> workers) {
        for (Future<?> worker : workers) if (!worker.isDone()) worker.cancel(true);
        pool.shutdownNow();
        try { return pool.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("coordination timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private boolean attemptCreate(RefundPersistenceService.CreateCommand command) {
        try { auditedRequests.create(command); return true; }
        catch (DataIntegrityViolationException | IllegalArgumentException expected) { return false; }
    }

    private boolean attemptProvider(Long requestId, Integer userId) {
        try {
            persistence.prepareProviderAttempt(requestId, userId, "vnpay", "slot-" + UUID.randomUUID(), NOW);
            return true;
        } catch (DataIntegrityViolationException | IllegalStateException expected) { return false; }
    }

    private RefundRequestJpaEntity approved(RefundRequestJpaEntity row) {
        tx().executeWithoutResult(status -> requests.findForUpdate(row.getId(), row.getUserId()).orElseThrow()
                .approve(row.getUserId(), NOW));
        return tx().execute(status -> requests.findById(row.getId()).orElseThrow());
    }

    private RefundPersistenceService.CreateCommand command(Fixture f, String key, String fingerprint, int quantity) {
        return new RefundPersistenceService.CreateCommand(f.userId, f.orderId, f.itemId, key, fingerprint,
                quantity, "REQUEST", null, false, NOW);
    }

    private RefundEventStoreService.EventCommand eventCommand(Long requestId, Integer userId, String fingerprint) {
        return new RefundEventStoreService.EventCommand(requestId, userId, null, "ADMIN_APPROVAL", "approval-1",
                fingerprint, A, C, null, NOW, NOW);
    }

    private Fixture committedFixture(OrderItemType type, int quantity, long unitPrice) {
        return tx().execute(status -> fixture(type, quantity, unitPrice));
    }

    private Fixture fixture(OrderItemType type, int quantity, long unitPrice) {
        User user = users.saveAndFlush(User.builder().fullName("Refund V10 MySQL fixture")
                .email("refund-v10-" + UUID.randomUUID() + "@example.test").passwordHash("test-only")
                .role(Role.USER).isActive(true).build());
        var order = OrderJpaEntity.fromSnapshot(Order.create(user.getId(),
                List.of(new OrderLineSnapshot(type, 700_000 + user.getId(), "Refund V10 fixture", quantity,
                        unitPrice, true)), 0, Optional.empty(), Clock.fixed(NOW.minusSeconds(60), ZoneOffset.UTC)));
        em.persist(order); em.flush();
        var item = order.getItems().getFirst();
        var stored = new OrderPersistenceMapper().toDomain(order);
        var attempt = PaymentAttemptJpaEntity.newSnapshot(PaymentAttempt.create(order.getId(), stored, "vnpay",
                "refund-v10-" + UUID.randomUUID(), Clock.fixed(NOW.minusSeconds(30), ZoneOffset.UTC)));
        em.persist(attempt); em.flush();
        jdbc.update("UPDATE payment_attempts SET status='SUCCEEDED',transaction_reference=?,succeeded_at=?,updated_at=? WHERE id=?",
                "txn-" + UUID.randomUUID(), NOW.minusSeconds(5), NOW.minusSeconds(5), attempt.getId());
        em.clear();
        var event = new PaymentEvent("vnpay", "refund-v10-event-" + UUID.randomUUID(), attempt.getMerchantReference(),
                "txn-event-" + UUID.randomUUID(), stored.getTotalVnd(), "VND", PaymentEvent.Outcome.SUCCEEDED,
                NOW.minusSeconds(6), NOW.minusSeconds(5), A, "verified-test");
        var audit = new PaymentEventAudit(null, attempt.getId(), order.getId(), user.getId(), event, event.fingerprint(),
                PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, PaymentEventAudit.Disposition.APPLIED,
                PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
        var eventRow = PaymentEventJpaEntity.newSnapshot(audit); em.persist(eventRow); em.flush();
        var settlement = settlements.saveAndFlush(PaymentSettlementJpaEntity.record(order.getId(), attempt.getId(),
                eventRow.getId(), NOW.minusSeconds(6), NOW.minusSeconds(4)));
        return new Fixture(user.getId(), order.getId(), item.getId(), attempt.getId(), eventRow.getId(), settlement.getId());
    }

    private void cleanup(Fixture f) {
        tx().executeWithoutResult(status -> {
            jdbc.update("DELETE FROM refund_review_cases WHERE refund_request_id IN (SELECT id FROM refund_requests WHERE order_id=?)", f.orderId);
            jdbc.update("DELETE FROM refund_events WHERE refund_request_id IN (SELECT id FROM refund_requests WHERE order_id=?)", f.orderId);
            jdbc.update("DELETE FROM refund_provider_attempts WHERE refund_request_id IN (SELECT id FROM refund_requests WHERE order_id=?)", f.orderId);
            jdbc.update("DELETE FROM refund_unit_allocations WHERE refund_request_id IN (SELECT id FROM refund_requests WHERE order_id=?)", f.orderId);
            jdbc.update("DELETE FROM refund_requests WHERE order_id=?", f.orderId);
            jdbc.update("DELETE FROM payment_settlements WHERE id=?", f.settlementId);
            jdbc.update("DELETE FROM payment_events WHERE id=?", f.eventId);
            jdbc.update("DELETE FROM payment_attempts WHERE id=?", f.attemptId);
            jdbc.update("DELETE FROM order_items WHERE order_id=?", f.orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", f.orderId);
            jdbc.update("DELETE FROM users WHERE id=?", f.userId);
        });
    }

    private long countRequests(Integer orderId) {
        return tx().execute(status -> jdbc.queryForObject("SELECT COUNT(*) FROM refund_requests WHERE order_id=?",
                Long.class, orderId));
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private record Fixture(Integer userId, Integer orderId, Integer itemId, Integer attemptId,
                           Integer eventId, Integer settlementId) { }
}
