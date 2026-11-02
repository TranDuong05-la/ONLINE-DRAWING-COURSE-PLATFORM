package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.refund.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ArtAcademyApplication.class,
        properties = "spring.datasource.url=jdbc:h2:mem:refundfoundation;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class RefundPersistenceFoundationH2IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-01T01:02:03.123456Z");
    private static final String A = "a".repeat(64), B = "b".repeat(64), C = "c".repeat(64), D = "d".repeat(64);
    private static final AtomicInteger USERS = new AtomicInteger(50000);

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orders;
    @Autowired PaymentSettlementRepository settlements;
    @Autowired RefundRequestRepository requests;
    @Autowired RefundUnitAllocationRepository units;
    @Autowired RefundProviderAttemptRepository providerAttempts;
    @Autowired RefundReviewCaseRepository reviews;
    @Autowired RefundPersistenceService persistence;
    @Autowired RefundAuditedRequestService auditedRequests;
    @Autowired RefundAuditedEventService auditedEvents;
    @Autowired RefundConflictReviewService conflictReviews;

    @Test
    void schemaContainsFiveTablesCompositeRoutesAndNullableUniqueMarker() {
        for (String table : List.of("REFUND_REQUESTS", "REFUND_UNIT_ALLOCATIONS", "REFUND_PROVIDER_ATTEMPTS",
                "REFUND_EVENTS", "REFUND_REVIEW_CASES"))
            assertEquals(1L, jdbc.queryForObject("select count(*) from information_schema.tables where table_name=?", Long.class, table));
        var constraints = jdbc.queryForList("select constraint_name from information_schema.table_constraints", String.class);
        assertTrue(constraints.stream().anyMatch("UK_ORDERS_ID_USER"::equalsIgnoreCase));
        assertTrue(constraints.stream().anyMatch("UK_ORDER_ITEMS_ID_ORDER"::equalsIgnoreCase));
        assertTrue(constraints.stream().anyMatch("UK_PAYMENT_SETTLEMENTS_ID_ORDER"::equalsIgnoreCase));
        assertTrue(constraints.stream().anyMatch("FK_REFUND_REQUEST_OWNER_ROUTE"::equalsIgnoreCase));
    }

    @Test
    void productCreationAllocatesLowestUnitsAndOwnerKeyReplays() {
        var f = fixture(OrderItemType.PRODUCT, 4, 101);
        var first = auditedRequests.create(command(f, "key", A, 2));
        assertEquals(List.of(0, 1), first.allocations().stream().map(RefundUnitAllocationJpaEntity::getUnitPosition).toList());
        assertEquals(202, first.request().getRequestedAmountVnd());
        var replay = auditedRequests.create(command(f, "key", A, 2));
        assertTrue(replay.idempotentReplay());
        assertEquals(first.request().getId(), replay.request().getId());
    }

    @Test
    void sameOwnerKeyWithDifferentFingerprintCommitsReviewBeforeTypedConflict() {
        var f = fixture(OrderItemType.PRODUCT, 2, 100);
        var original = auditedRequests.create(command(f, "conflict", A, 1));
        var conflict = assertThrows(RefundAuditedRequestService.DurableRequestConflict.class,
                () -> auditedRequests.create(command(f, "conflict", B, 1)));
        var review = tx().execute(s -> reviews.findById(conflict.reviewCaseId()).orElseThrow());
        assertEquals(original.request().getId(), review.getRefundRequestId());
        assertEquals("REQUEST_IDEMPOTENCY_CONFLICT", review.getCaseType());
        assertEquals(B, review.getConflictingFingerprint());
    }

    @Test
    void concurrentRequestInsertRaceResolutionReusesOneDurableConflictCase() throws Exception {
        var f = fixture(OrderItemType.PRODUCT, 2, 100);
        var original = auditedRequests.create(command(f, "race-conflict", A, 1));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> resolveRequestRace(f.userId, ready, start));
            var two = pool.submit(() -> resolveRequestRace(f.userId, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var first = one.get(10, TimeUnit.SECONDS);
            var second = two.get(10, TimeUnit.SECONDS);
            assertEquals(original.request().getId(), first.getRefundRequestId());
            assertEquals(first.getId(), second.getId());
        }
        assertEquals(1, reviews.findAllByRefundRequestIdOrderById(original.request().getId()).size());
    }

    @Test
    void concurrentEventInsertRaceResolutionReusesOneDurableConflictCase() throws Exception {
        var f = fixture(OrderItemType.COURSE, 1, 100);
        var request = auditedRequests.create(command(f, "event-race-conflict", A, 1)).request();
        var original = eventCommand(request.getId(), f.userId, B);
        assertFalse(auditedEvents.record(original).idempotentReplay());
        var changed = new RefundEventStoreService.EventCommand(request.getId(), f.userId, null, "ADMIN_APPROVAL",
                "approval-1", C, A, D, null, NOW, NOW.plusSeconds(1));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> resolveEventRace(changed, ready, start));
            var two = pool.submit(() -> resolveEventRace(changed, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var first = one.get(10, TimeUnit.SECONDS);
            var second = two.get(10, TimeUnit.SECONDS);
            assertEquals(first.getId(), second.getId());
        }
        assertEquals(1, reviews.findAllByRefundRequestIdOrderById(request.getId()).size());
    }

    @Test
    void releasedUnitHistoryIsPreservedAndReuseCreatesNewRow() {
        var f = fixture(OrderItemType.PRODUCT, 2, 100);
        var first = auditedRequests.create(command(f, "release-1", A, 1));
        tx().executeWithoutResult(s -> {
            units.findById(first.allocations().getFirst().getId()).orElseThrow().release(NOW.plusSeconds(1));
            jdbc.update("update refund_requests set amount_reservation_status='RELEASED' where id=?", first.request().getId());
        });
        var second = auditedRequests.create(command(f, "release-2", B, 1));
        assertEquals(0, second.allocations().getFirst().getUnitPosition());
        assertNotEquals(first.allocations().getFirst().getId(), second.allocations().getFirst().getId());
        assertEquals(2L, jdbc.queryForObject("select count(*) from refund_unit_allocations where order_item_id=? and unit_position=0", Long.class, f.itemId));
    }

    @Test
    void activeAndRefundedUnitsRemainUniqueAcrossRequests() {
        var f = fixture(OrderItemType.PRODUCT, 3, 100);
        var first = auditedRequests.create(command(f, "active-1", A, 1));
        var second = auditedRequests.create(command(f, "active-2", B, 1));
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(s ->
                jdbc.update("update refund_unit_allocations set unit_position=0 where id=?", second.allocations().getFirst().getId())));
        tx().executeWithoutResult(s -> units.findById(first.allocations().getFirst().getId()).orElseThrow().markRefunded());
        var third = auditedRequests.create(command(f, "active-3", C, 1));
        assertEquals(2, third.allocations().getFirst().getUnitPosition());
    }

    @Test
    void courseUsesWholeItemPositionZeroAndPositiveAmount() {
        var f = fixture(OrderItemType.COURSE, 1, 12345);
        var result = auditedRequests.create(command(f, "course", A, 1));
        assertEquals(0, result.allocations().getFirst().getUnitPosition());
        assertEquals(12345, result.request().getRequestedAmountVnd());
        assertEquals(RefundPersistenceTypes.ScopeType.STANDALONE_COURSE, result.request().getScopeType());
    }

    @Test
    void zeroNetFinancialRequestAndCapturedMismatchFailClosed() {
        var zero = fixture(OrderItemType.PRODUCT, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> auditedRequests.create(command(zero, "zero", A, 1)));
        var mismatch = fixture(OrderItemType.PRODUCT, 1, 100);
        jdbc.update("update payment_attempts set expected_amount_vnd=99 where id=?", mismatch.attemptId);
        assertThrows(IllegalStateException.class, () -> auditedRequests.create(command(mismatch, "mismatch", A, 1)));
    }

    @Test
    void allocationAndCapturedCapsRejectUnavailableQuantity() {
        var f = fixture(OrderItemType.PRODUCT, 2, 100);
        auditedRequests.create(command(f, "all", A, 2));
        assertThrows(IllegalArgumentException.class, () -> auditedRequests.create(command(f, "too-much", B, 1)));
        assertEquals(200L, jdbc.queryForObject("select sum(requested_amount_vnd) from refund_requests where order_id=?", Long.class, f.orderId));
    }

    @Test
    void unknownKeepsMoneyReservationAndExecutionSlot() {
        var f = fixture(OrderItemType.PRODUCT, 1, 100);
        var request = approved(auditedRequests.create(command(f, "unknown", A, 1)).request());
        var attempt = persistence.prepareProviderAttempt(request.getId(), f.userId, "vnpay", "op-" + UUID.randomUUID(), NOW);
        persistence.startProviderAttempt(request.getId(), f.userId, attempt.getId(), NOW.plusSeconds(1));
        persistence.recordUnknown(request.getId(), f.userId, attempt.getId(), B, "timeout", NOW.plusSeconds(2));
        var stored = tx().execute(s -> requests.findById(request.getId()).orElseThrow());
        var storedAttempt = tx().execute(s -> providerAttempts.findById(attempt.getId()).orElseThrow());
        assertEquals(RefundPersistenceTypes.FinancialStatus.UNKNOWN, stored.getFinancialStatus());
        assertEquals(RefundPersistenceTypes.AmountReservationStatus.RESERVED, stored.getAmountReservationStatus());
        assertEquals(Byte.valueOf((byte) 1), storedAttempt.getExecutionSlot());
        assertThrows(IllegalStateException.class, () -> persistence.prepareProviderAttempt(request.getId(), f.userId,
                "vnpay", "op-second", NOW.plusSeconds(3)));
    }

    @Test
    void succeededProviderAttemptIsTerminalAndNeverReleasesSlot() {
        var f = fixture(OrderItemType.PRODUCT, 1, 100);
        var request = approved(auditedRequests.create(command(f, "success", A, 1)).request());
        var attempt = persistence.prepareProviderAttempt(request.getId(), f.userId, "vnpay", "op-" + UUID.randomUUID(), NOW);
        tx().executeWithoutResult(s -> {
            var managed = providerAttempts.findForUpdate(attempt.getId(), request.getId()).orElseThrow();
            managed.start(NOW.plusSeconds(1)); managed.succeed("provider-ref", B, "success-evidence", NOW.plusSeconds(2));
            assertThrows(IllegalStateException.class, () -> managed.authorizeRetry(NOW.plusSeconds(3)));
        });
        assertEquals(Byte.valueOf((byte) 1),
                tx().execute(s -> providerAttempts.findById(attempt.getId()).orElseThrow().getExecutionSlot()));
    }

    @Test
    void eventReplayIsIdempotentAndChangedFingerprintCreatesDurableReview() {
        var f = fixture(OrderItemType.COURSE, 1, 100);
        var request = auditedRequests.create(command(f, "event", A, 1)).request();
        var original = eventCommand(request.getId(), f.userId, B);
        assertFalse(auditedEvents.record(original).idempotentReplay());
        assertTrue(auditedEvents.record(original).idempotentReplay());
        var changed = new RefundEventStoreService.EventCommand(request.getId(), f.userId, null, "ADMIN_APPROVAL",
                "approval-1", C, A, D, null, NOW, NOW.plusSeconds(1));
        var conflict = assertThrows(RefundAuditedEventService.DurableEventConflict.class, () -> auditedEvents.record(changed));
        var review = tx().execute(s -> reviews.findById(conflict.reviewCaseId()).orElseThrow());
        assertEquals("EVENT_IDENTITY_CONFLICT", review.getCaseType());
        assertEquals(C, review.getConflictingFingerprint());
    }

    @Test
    void courseAndPhysicalEffectStatesRemainIndependent() {
        var f = fixture(OrderItemType.COURSE, 1, 100);
        var request = auditedRequests.create(new RefundPersistenceService.CreateCommand(f.userId, f.orderId, f.itemId,
                "effects", A, 1, "REQUEST", null, true, NOW));
        assertEquals(RefundPersistenceTypes.CourseAccessStatus.UNCHANGED, request.request().getCourseAccessStatus());
        assertEquals(RefundPersistenceTypes.PhysicalStatus.PENDING, request.request().getPhysicalStatus());
        assertEquals(RefundPersistenceTypes.ShipmentHoldStatus.ACTIVE, request.request().getShipmentHoldStatus());
        assertEquals(RefundPersistenceTypes.FinancialStatus.NOT_STARTED, request.request().getFinancialStatus());
    }

    @Test
    void routeForeignKeyAndRollbackPreventPartialPersistence() {
        var f = fixture(OrderItemType.PRODUCT, 1, 100);
        var created = auditedRequests.create(command(f, "route", A, 1));
        assertThrows(DataIntegrityViolationException.class, () -> tx().executeWithoutResult(s ->
                jdbc.update("update refund_requests set order_id=? where id=?", f.orderId + 9999, created.request().getId())));
        var rollbackFixture = fixture(OrderItemType.PRODUCT, 1, 100);
        var rollback = command(rollbackFixture, "rollback", B, 1);
        tx().executeWithoutResult(s -> { persistence.create(rollback); s.setRollbackOnly(); });
        assertTrue(requests.findByUserIdAndIdempotencyKey(rollbackFixture.userId, "rollback").isEmpty());
    }

    @Test
    void concurrentRequestsCannotReserveTheSameUnit() throws Exception {
        var f = fixture(OrderItemType.PRODUCT, 1, 100);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return attemptCreate(command(f, "race-1", A, 1)); });
            var two = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return attemptCreate(command(f, "race-2", B, 1)); });
            start.countDown();
            assertEquals(1, (one.get(10, TimeUnit.SECONDS) ? 1 : 0) + (two.get(10, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1L, jdbc.queryForObject("select count(*) from refund_unit_allocations where order_item_id=? and active_marker=1", Long.class, f.itemId));
    }

    private boolean attemptCreate(RefundPersistenceService.CreateCommand command) {
        try { persistence.create(command); return true; } catch (RuntimeException expected) { return false; }
    }

    private RefundReviewCaseJpaEntity resolveRequestRace(Integer userId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timed out");
        var result = conflictReviews.resolveRequestInsertRace(userId, "race-conflict", B, NOW);
        return result.conflictReview();
    }

    private RefundReviewCaseJpaEntity resolveEventRace(RefundEventStoreService.EventCommand command,
                                                        CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timed out");
        var result = conflictReviews.resolveEventInsertRace(command);
        return result.conflictReview();
    }

    private RefundRequestJpaEntity approved(RefundRequestJpaEntity row) {
        tx().executeWithoutResult(s -> requests.findForUpdate(row.getId(), row.getUserId()).orElseThrow().approve(90001, NOW));
        return tx().execute(s -> requests.findById(row.getId()).orElseThrow());
    }

    private RefundPersistenceService.CreateCommand command(Fixture f, String key, String fingerprint, int quantity) {
        return new RefundPersistenceService.CreateCommand(f.userId, f.orderId, f.itemId, key, fingerprint,
                quantity, "REQUEST", null, false, NOW);
    }

    private RefundEventStoreService.EventCommand eventCommand(Long requestId, Integer userId, String fingerprint) {
        return new RefundEventStoreService.EventCommand(requestId, userId, null, "ADMIN_APPROVAL", "approval-1",
                fingerprint, A, C, null, NOW, NOW);
    }

    private Fixture fixture(OrderItemType type, int quantity, long unitPrice) {
        int userId = USERS.incrementAndGet();
        return tx().execute(s -> {
            var order = OrderJpaEntity.fromSnapshot(Order.create(userId,
                    List.of(new OrderLineSnapshot(type, userId, "Refund fixture", quantity, unitPrice, true)),
                    0, Optional.empty(), Clock.fixed(NOW.minusSeconds(60), ZoneOffset.UTC)));
            em.persist(order); em.flush();
            var item = order.getItems().getFirst();
            var stored = new OrderPersistenceMapper().toDomain(order);
            var attempt = PaymentAttemptJpaEntity.newSnapshot(PaymentAttempt.create(order.getId(), stored, "vnpay",
                    "refund-" + UUID.randomUUID(), Clock.fixed(NOW.minusSeconds(30), ZoneOffset.UTC)));
            em.persist(attempt); em.flush();
            jdbc.update("update payment_attempts set status='SUCCEEDED', transaction_reference=?, succeeded_at=?, updated_at=? where id=?",
                    "txn-" + UUID.randomUUID(), NOW.minusSeconds(5), NOW.minusSeconds(5), attempt.getId());
            em.clear();
            var event = new PaymentEvent("vnpay", "refund-event-" + UUID.randomUUID(), attempt.getMerchantReference(),
                    "txn-event-" + UUID.randomUUID(), stored.getTotalVnd(), "VND", PaymentEvent.Outcome.SUCCEEDED,
                    NOW.minusSeconds(6), NOW.minusSeconds(5), A, "verified-test");
            var audit = new PaymentEventAudit(null, attempt.getId(), order.getId(), userId, event, event.fingerprint(),
                    PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, PaymentEventAudit.Disposition.APPLIED,
                    PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
            var eventRow = PaymentEventJpaEntity.newSnapshot(audit); em.persist(eventRow); em.flush();
            var settlement = settlements.saveAndFlush(PaymentSettlementJpaEntity.record(order.getId(), attempt.getId(),
                    eventRow.getId(), NOW.minusSeconds(6), NOW.minusSeconds(4)));
            return new Fixture(userId, order.getId(), item.getId(), attempt.getId(), settlement.getId());
        });
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private record Fixture(Integer userId, Integer orderId, Integer itemId, Integer attemptId, Integer settlementId) { }
}
