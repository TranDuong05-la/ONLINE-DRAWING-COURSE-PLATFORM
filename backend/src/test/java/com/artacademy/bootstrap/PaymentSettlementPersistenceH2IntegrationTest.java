package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Hibernate-managed isolated H2 persistence checks; these do not exercise Flyway/MySQL V8. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class PaymentSettlementPersistenceH2IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-28T03:04:05.123456Z");
    private static final String DIGEST = "a".repeat(64);

    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentEventRepository events;
    @Autowired private PaymentSettlementRepository settlements;
    @Autowired private PaymentReconciliationCaseRepository cases;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void settlementPersistsAndReadsBackUtcTimesAndReferences() {
        var fixture = fixture(12101);
        var id = tx().execute(status -> {
            var row = settlements.saveAndFlush(PaymentSettlementJpaEntity.record(
                    fixture.orderId, fixture.attemptId, fixture.eventId, NOW.minusSeconds(1), NOW));
            return row.getId();
        });
        var found = tx().execute(status -> settlements.findById(id).orElseThrow());
        assertEquals(fixture.orderId, found.getOrderId());
        assertEquals(fixture.attemptId, found.getPaymentAttemptId());
        assertEquals(fixture.eventId, found.getPaymentEventId());
        assertEquals(NOW.minusSeconds(1), found.getProviderPaidAt());
        assertEquals(NOW, found.getSettledAt());
    }

    @Test
    void uniqueOrderAttemptAndEventKeysPreventDuplicateSettlement() {
        var fixture = fixture(12102);
        tx().executeWithoutResult(status -> settlements.saveAndFlush(PaymentSettlementJpaEntity.record(
                fixture.orderId, fixture.attemptId, fixture.eventId, NOW.minusSeconds(1), NOW)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status ->
                settlements.saveAndFlush(PaymentSettlementJpaEntity.record(
                        fixture.orderId, fixture.attemptId, fixture.eventId, NOW.minusSeconds(1), NOW.plusSeconds(1)))));

        var keys = jdbc.queryForList("select constraint_name from information_schema.table_constraints " +
                "where table_name='PAYMENT_SETTLEMENTS' and constraint_type='UNIQUE'", String.class);
        assertTrue(keys.stream().anyMatch(name -> name.equalsIgnoreCase("uk_payment_settlement_order")));
        assertTrue(keys.stream().anyMatch(name -> name.equalsIgnoreCase("uk_payment_settlement_attempt")));
        assertTrue(keys.stream().anyMatch(name -> name.equalsIgnoreCase("uk_payment_settlement_event")));
    }

    @Test
    void compositeForeignKeysRejectAttemptOrEventRoutedToAnotherOrder() {
        var actual = fixture(12103);
        var other = fixture(12104);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status ->
                settlements.saveAndFlush(PaymentSettlementJpaEntity.record(
                        other.orderId, actual.attemptId, actual.eventId, NOW.minusSeconds(1), NOW))));

        Integer alternateEvent = eventForAnotherAttemptOnSameOrder(actual.orderId, 12103);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status ->
                settlements.saveAndFlush(PaymentSettlementJpaEntity.record(
                        actual.orderId, actual.attemptId, alternateEvent, NOW.minusSeconds(1), NOW))));
    }

    @Test
    void unknownMerchantCasePersistsWithoutOrderAttemptOrEventReferences() {
        var id = tx().execute(status -> cases.saveAndFlush(PaymentReconciliationCaseJpaEntity.unknownMerchant(
                "vnpay", "merchant-unknown:payload:" + DIGEST, "UNKNOWN_MERCHANT_REFERENCE", "v1:" + DIGEST,
                "merchant-unknown", 12_345_00L, "SUCCEEDED", "MISSING", null, NOW, DIGEST)).getId());
        var row = tx().execute(status -> cases.findById(id).orElseThrow());
        assertNull(row.getOrderId());
        assertNull(row.getPaymentAttemptId());
        assertNull(row.getPaymentEventId());
        assertNull(row.getProviderPaidAt());
        assertEquals("MISSING", row.getProviderTimestampState());
    }

    @Test
    void reconciliationCaseIdentityIsUniqueAndReplayLookupReturnsOriginal() {
        var fixture = fixture(12105);
        var firstId = tx().execute(status -> cases.saveAndFlush(PaymentReconciliationCaseJpaEntity.linked(
                "vnpay", "merchant-1:txn-1", "LATE_SUCCESS", "v1:" + DIGEST,
                fixture.eventId, null, fixture.attemptId, fixture.orderId, null, NOW)).getId());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> tx().executeWithoutResult(status ->
                cases.saveAndFlush(PaymentReconciliationCaseJpaEntity.linked(
                        "vnpay", "merchant-1:txn-1", "LATE_SUCCESS", "v1:" + DIGEST,
                        fixture.eventId, null, fixture.attemptId, fixture.orderId, null, NOW.plusSeconds(5)))));
        var replay = tx().execute(status -> cases.findByProviderAndEventReferenceAndCaseTypeAndEvidenceFingerprint(
                "vnpay", "merchant-1:txn-1", "LATE_SUCCESS", "v1:" + DIGEST).orElseThrow());
        assertEquals(firstId, replay.getId());
    }

    @Test
    void transactionRollbackLeavesNoSettlement() {
        var fixture = fixture(12106);
        tx().executeWithoutResult(status -> {
            settlements.saveAndFlush(PaymentSettlementJpaEntity.record(
                    fixture.orderId, fixture.attemptId, fixture.eventId, NOW.minusSeconds(1), NOW));
            status.setRollbackOnly();
        });
        assertTrue(Boolean.TRUE.equals(tx().execute(status -> settlements.findByOrderId(fixture.orderId).isEmpty())));
    }

    private Fixture fixture(int userId) {
        var orderId = tx().execute(status -> {
            var order = OrderJpaEntity.fromSnapshot(Order.create(userId,
                    List.of(new OrderLineSnapshot(OrderItemType.COURSE, 88, "Persistence fixture", 1, 12_345, true)),
                    0, Optional.empty(), Clock.fixed(NOW.minusSeconds(30), ZoneOffset.UTC)));
            entityManager.persist(order);
            entityManager.flush();
            return order.getId();
        });
        var ids = tx().execute(status -> {
            var order = orders.findByIdAndUserId(orderId, userId).orElseThrow();
            var stored = new OrderPersistenceMapper().toDomain(order);
            var attempt = PaymentAttemptJpaEntity.newSnapshot(PaymentAttempt.create(orderId, stored, "vnpay",
                    "merchant-" + userId, Clock.fixed(NOW.minusSeconds(20), ZoneOffset.UTC)));
            entityManager.persist(attempt);
            entityManager.flush();
            var event = new PaymentEvent("vnpay", "merchant-" + userId + ":txn-" + userId,
                    attempt.getMerchantReference(), "txn-" + userId, attempt.getExpectedAmountVnd(), "VND",
                    PaymentEvent.Outcome.SUCCEEDED, NOW.minusSeconds(2), NOW.minusSeconds(1), DIGEST, "verified-test");
            var audit = new PaymentEventAudit(null, attempt.getId(), orderId, userId, event, event.fingerprint(),
                    PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, PaymentEventAudit.Disposition.APPLIED,
                    PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
            var eventRow = PaymentEventJpaEntity.newSnapshot(audit);
            entityManager.persist(eventRow);
            entityManager.flush();
            return new Fixture(orderId, attempt.getId(), eventRow.getId());
        });
        return ids;
    }

    private Integer eventForAnotherAttemptOnSameOrder(Integer orderId, int userId) {
        return tx().execute(status -> {
            var order = orders.findByIdAndUserId(orderId, userId).orElseThrow();
            var stored = new OrderPersistenceMapper().toDomain(order);
            String suffix = "alternate-" + userId;
            var attempt = PaymentAttemptJpaEntity.newSnapshot(PaymentAttempt.create(orderId, stored, "vnpay",
                    "merchant-" + suffix, Clock.fixed(NOW.minusSeconds(19), ZoneOffset.UTC)));
            entityManager.persist(attempt);
            entityManager.flush();
            var event = new PaymentEvent("vnpay", "merchant-" + suffix + ":txn", attempt.getMerchantReference(),
                    "txn-" + suffix, attempt.getExpectedAmountVnd(), "VND", PaymentEvent.Outcome.SUCCEEDED,
                    NOW.minusSeconds(2), NOW.minusSeconds(1), DIGEST, "verified-test");
            var audit = new PaymentEventAudit(null, attempt.getId(), orderId, userId, event, event.fingerprint(),
                    PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, PaymentEventAudit.Disposition.APPLIED,
                    PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
            var eventRow = PaymentEventJpaEntity.newSnapshot(audit);
            entityManager.persist(eventRow);
            entityManager.flush();
            return eventRow.getId();
        });
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private record Fixture(Integer orderId, Integer attemptId, Integer eventId) { }
}
