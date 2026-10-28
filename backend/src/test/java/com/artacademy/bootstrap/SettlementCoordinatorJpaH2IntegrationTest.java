package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.payment.vnpay.IpnClassification;
import com.artacademy.payment.vnpay.IpnReconciliationResult;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import com.drawingcourse.backend.service.settlement.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** H2 integration coverage for coordinator atomicity; adapter effects are rows in a test-only table. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class SettlementCoordinatorJpaH2IntegrationTest {
    private static final int USER_ID = 7401;
    private static final Instant CREATED = Instant.parse("2026-10-28T03:00:00Z");
    private static final Instant PAID = CREATED.plusSeconds(10);
    private static final Instant RECEIVED = CREATED.plusSeconds(11);
    private static final Instant SETTLED = CREATED.plusSeconds(12);

    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentEventRepository events;
    @Autowired private PaymentSettlementRepository settlements;
    @Autowired private JpaSettlementLedgerAdapter jpaLedger;

    private TestParticipants participants;
    private SettlementCoordinator coordinator;

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS settlement_test_effects (kind VARCHAR(24) NOT NULL, order_id INT NOT NULL, item_id INT NOT NULL)");
        jdbc.update("DELETE FROM settlement_test_effects");
        participants = new TestParticipants(jdbc);
        coordinator = coordinator(jpaLedger, participants);
    }

    @Test
    void productionCoordinatorIsNotRegisteredWithoutExplicitEnablementAndRequiredBceAdapters() {
        assertFalse(applicationContext.getBeansOfType(SettlementCoordinator.class).size() > 0);
    }

    @Test
    void successfulSettlementConfirmsOrderAndAppliesAllLocalEffectsOnce() {
        var fixture = fixture();
        var result = coordinator.settle(fixture.validResult());

        assertEquals(SettlementResult.Outcome.SETTLED, result.outcome());
        assertEquals(OrderStatus.CONFIRMED, order(fixture).getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, order(fixture).getPaymentStatus());
        assertEquals(1, settlements.findByOrderId(fixture.orderId()).orElseThrow().getPaymentEventId()
                .intValue() == fixture.eventId() ? 1 : 0);
        assertEquals(1, effects("voucher", fixture.orderId()));
        assertEquals(1, effects("inventory", fixture.orderId()));
        assertEquals(1, effects("course", fixture.orderId()));

        var duplicate = coordinator.settle(fixture.duplicateResult());
        assertEquals(SettlementResult.Outcome.ALREADY_SETTLED, duplicate.outcome());
        assertEquals(1, effects("voucher", fixture.orderId()));
        assertEquals(1, effects("inventory", fixture.orderId()));
        assertEquals(1, effects("course", fixture.orderId()));
    }

    @Test
    void concurrentDuplicateEventsSerializeAndSettleOnlyOnce() throws Exception {
        var fixture = fixture();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<SettlementResult> task = () -> { start.await(3, TimeUnit.SECONDS); return coordinator.settle(fixture.validResult()); };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            var outcomes = Set.of(first.get(8, TimeUnit.SECONDS).outcome(), second.get(8, TimeUnit.SECONDS).outcome());
            assertEquals(Set.of(SettlementResult.Outcome.SETTLED, SettlementResult.Outcome.ALREADY_SETTLED), outcomes);
            assertEquals(1, settlements.findByOrderId(fixture.orderId()).stream().count());
            assertEquals(1, effects("voucher", fixture.orderId()));
            assertEquals(1, effects("inventory", fixture.orderId()));
            assertEquals(1, effects("course", fixture.orderId()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void differentSuccessfulEventAfterSettlementRequiresReviewWithoutRepeatingEffects() {
        var original = fixture();
        assertEquals(SettlementResult.Outcome.SETTLED, coordinator.settle(original.validResult()).outcome());
        var secondEvent = additionalSuccessfulAttempt(original);

        var result = coordinator.settle(secondEvent.validResult());

        assertEquals(SettlementResult.Outcome.REQUIRES_REVIEW, result.outcome());
        assertEquals(1, effects("voucher", original.orderId()));
        assertEquals(1, effects("inventory", original.orderId()));
        assertEquals(1, effects("course", original.orderId()));
    }

    @Test
    void existingLedgerWithUnconfirmedOrderRequiresReview() {
        var fixture = fixture();
        tx().executeWithoutResult(status -> jpaLedger.insert(fixture.orderId(), fixture.attemptId(),
                fixture.eventId(), PAID, SETTLED));

        var result = coordinator.settle(fixture.validResult());

        assertEquals(SettlementResult.Outcome.REQUIRES_REVIEW, result.outcome());
        assertPendingWithoutLedgerCheck(fixture);
        assertEquals(0, effects("voucher", fixture.orderId()) + effects("inventory", fixture.orderId())
                + effects("course", fixture.orderId()));
    }

    @Test
    void retryableFailureIsReportedOnlyAfterCoordinatorOwnedRollbackCompletes() {
        participants.transientFailAt = "inventory";
        var fixture = fixture();

        var result = coordinator.settle(fixture.validResult());

        assertEquals(SettlementResult.Outcome.RETRYABLE_FAILURE, result.outcome());
        assertPending(fixture);
    }

    @Test
    void transientFailureJoiningOuterTransactionPropagatesUntilOuterRollback() {
        participants.transientFailAt = "inventory";
        var fixture = fixture();

        assertThrows(TestTransientPersistenceFailure.class,
                () -> tx().executeWithoutResult(status -> coordinator.settle(fixture.validResult())));

        assertPending(fixture);
    }

    @Test
    void courseFailureRollsBackOrderVoucherInventoryAndAllEffectRows() {
        participants.failAt = "course";
        var fixture = fixture();
        assertThrows(IllegalStateException.class, () -> coordinator.settle(fixture.validResult()));
        assertPending(fixture);
    }

    @Test
    void inventoryFailureRollsBackOrderVoucherAndAllEffectRows() {
        participants.failAt = "inventory";
        var fixture = fixture();
        assertThrows(IllegalStateException.class, () -> coordinator.settle(fixture.validResult()));
        assertPending(fixture);
    }

    @Test
    void ledgerFailureRollsBackOrderAndAllPreviouslyAppliedEffects() {
        var fixture = fixture();
        SettlementLedgerPort failAfterInsert = new SettlementLedgerPort() {
            @Override public Optional<SettlementReference> findForUpdateByOrderId(Integer orderId) {
                return jpaLedger.findForUpdateByOrderId(orderId);
            }
            @Override public SettlementReference insert(Integer orderId, Integer attemptId, Integer eventId,
                    Instant providerPaidAt, Instant settledAt) {
                jpaLedger.insert(orderId, attemptId, eventId, providerPaidAt, settledAt);
                throw new IllegalStateException("Injected ledger failure after insert");
            }
        };
        coordinator = coordinator(failAfterInsert, participants);

        assertThrows(IllegalStateException.class, () -> coordinator.settle(fixture.validResult()));
        assertPending(fixture);
    }

    @Test
    void lateAndConflictingCallbacksRequireReviewWithoutEffects() {
        var lateFixture = fixture();
        var late = coordinator.settle(new IpnReconciliationResult(IpnClassification.LATE_PROVIDER_SUCCESS,
                lateFixture.attemptId(), lateFixture.orderId(), lateFixture.eventId(), null,
                PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,
                PaymentAttempt.ReviewReason.LATE_SUCCESS, false));
        assertEquals(SettlementResult.Outcome.REQUIRES_REVIEW, late.outcome());
        assertPending(lateFixture);

        var conflictFixture = fixture();
        var conflict = coordinator.settle(new IpnReconciliationResult(IpnClassification.CONFLICTING_CALLBACK,
                conflictFixture.attemptId(), conflictFixture.orderId(), conflictFixture.eventId(), 99,
                PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,
                PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS, false));
        assertEquals(SettlementResult.Outcome.REQUIRES_REVIEW, conflict.outcome());
        assertPending(conflictFixture);
    }

    private SettlementCoordinator coordinator(SettlementLedgerPort ledger, TestParticipants ports) {
        return new SettlementCoordinator(orders, attempts, events, ledger, ports, ports, ports,
                Clock.fixed(SETTLED, ZoneOffset.UTC), transactionManager);
    }

    private Fixture fixture() {
        return tx().execute(status -> {
            var orderSnapshot = Order.create(USER_ID,
                    List.of(new OrderLineSnapshot(OrderItemType.COURSE, 81, "Course snapshot", 1, 1000, true),
                            new OrderLineSnapshot(OrderItemType.PRODUCT, 82, "Product snapshot", 1, 1000, true)),
                    0, Optional.of(new OrderVoucher("SETTLE100", OrderVoucher.Kind.FIXED_VND, 100, 100)),
                    Clock.fixed(CREATED, ZoneOffset.UTC));
            var orderRow = OrderJpaEntity.fromSnapshot(orderSnapshot);
            entityManager.persist(orderRow);
            entityManager.flush();

            var pending = PaymentAttempt.create(orderRow.getId(), orderSnapshot, "vnpay",
                    "settlement-merchant-" + orderRow.getId(), Clock.fixed(CREATED.plusSeconds(2), ZoneOffset.UTC));
            var succeeded = new PaymentAttempt(null, pending.orderId(), pending.userId(), pending.provider(),
                    pending.merchantReference(), pending.expectedAmountVnd(), pending.currency(), pending.orderCreatedAt(),
                    pending.expiresAt(), pending.createdAt(), RECEIVED, PaymentStatus.SUCCEEDED,
                    "settlement-txn-" + orderRow.getId(), PAID, PaymentAttempt.ReviewReason.NONE);
            var attemptRow = PaymentAttemptJpaEntity.newSnapshot(succeeded);
            entityManager.persist(attemptRow);
            entityManager.flush();

            var event = new PaymentEvent("vnpay", succeeded.merchantReference() + ":" + succeeded.transactionReference(),
                    succeeded.merchantReference(), succeeded.transactionReference(), succeeded.expectedAmountVnd(),
                    "VND", PaymentEvent.Outcome.SUCCEEDED, PAID, RECEIVED, "c".repeat(64),
                    "vnpay-hmac-sha512;paydate=valid");
            var audit = new PaymentEventAudit(null, attemptRow.getId(), orderRow.getId(), USER_ID, event,
                    event.fingerprint(), PaymentStatus.PENDING, PaymentStatus.SUCCEEDED,
                    PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE,
                    OrderStatus.PENDING_PAYMENT);
            var eventRow = PaymentEventJpaEntity.newSnapshot(audit);
            entityManager.persist(eventRow);
            entityManager.flush();
            return new Fixture(orderRow.getId(), attemptRow.getId(), eventRow.getId());
        });
    }

    private Fixture additionalSuccessfulAttempt(Fixture original) {
        return tx().execute(status -> {
            var order = orders.findByIdAndUserId(original.orderId(), USER_ID).orElseThrow();
            Instant attemptCreated = CREATED.plusSeconds(3);
            var attempt = new PaymentAttempt(null, order.getId(), USER_ID, "vnpay",
                    "settlement-second-merchant-" + order.getId(), order.getTotalVnd(), "VND",
                    order.getCreatedAt(), order.getPaymentExpiresAt(), attemptCreated, RECEIVED,
                    PaymentStatus.SUCCEEDED, "settlement-second-txn-" + order.getId(), PAID,
                    PaymentAttempt.ReviewReason.NONE);
            var attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
            entityManager.persist(attemptRow);
            entityManager.flush();
            var event = new PaymentEvent("vnpay", attempt.merchantReference() + ":" + attempt.transactionReference(),
                    attempt.merchantReference(), attempt.transactionReference(), attempt.expectedAmountVnd(),
                    "VND", PaymentEvent.Outcome.SUCCEEDED, PAID, RECEIVED, "d".repeat(64),
                    "vnpay-hmac-sha512;paydate=valid");
            var audit = new PaymentEventAudit(null, attemptRow.getId(), order.getId(), USER_ID, event,
                    event.fingerprint(), PaymentStatus.PENDING, PaymentStatus.SUCCEEDED,
                    PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE,
                    OrderStatus.PENDING_PAYMENT);
            var eventRow = PaymentEventJpaEntity.newSnapshot(audit);
            entityManager.persist(eventRow);
            entityManager.flush();
            return new Fixture(order.getId(), attemptRow.getId(), eventRow.getId());
        });
    }

    private OrderJpaEntity order(Fixture fixture) {
        return tx().execute(status -> orders.findByIdAndUserId(fixture.orderId(), USER_ID).orElseThrow());
    }

    private void assertPending(Fixture fixture) {
        var persisted = order(fixture);
        assertEquals(OrderStatus.PENDING_PAYMENT, persisted.getStatus());
        assertEquals(PaymentStatus.CREATED, persisted.getPaymentStatus());
        assertTrue(settlements.findByOrderId(fixture.orderId()).isEmpty());
        assertNoEffects(fixture);
    }

    private void assertPendingWithoutLedgerCheck(Fixture fixture) {
        var persisted = order(fixture);
        assertEquals(OrderStatus.PENDING_PAYMENT, persisted.getStatus());
        assertEquals(PaymentStatus.CREATED, persisted.getPaymentStatus());
    }

    private void assertNoEffects(Fixture fixture) {
        assertEquals(0, effects("voucher", fixture.orderId()) + effects("inventory", fixture.orderId())
                + effects("course", fixture.orderId()));
    }

    private int effects(String kind, Integer orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_test_effects WHERE kind=? AND order_id=?",
                Integer.class, kind, orderId);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private record Fixture(Integer orderId, Integer attemptId, Integer eventId) {
        IpnReconciliationResult validResult() {
            return new IpnReconciliationResult(IpnClassification.VALID_ON_TIME_SUCCESS, attemptId, orderId,
                    eventId, null, PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE, false);
        }
        IpnReconciliationResult duplicateResult() {
            return new IpnReconciliationResult(IpnClassification.DUPLICATE_CALLBACK, attemptId, orderId,
                    eventId, null, PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE, true);
        }
    }

    private static final class TestParticipants implements CourseOwnershipConfirmationPort,
            InventoryReservationConfirmationPort, VoucherConsumptionPort {
        private final JdbcTemplate jdbc;
        private volatile String failAt;
        private volatile String transientFailAt;
        private TestParticipants(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override public void confirmCourseForOrder(Integer userId, Integer courseId, Integer orderId, Instant at) {
            effect("course", orderId, courseId);
        }
        @Override public void confirmProductForOrder(Integer userId, Integer productId, Integer orderId, Instant at) {
            effect("inventory", orderId, productId);
        }
        @Override public void consumeForOrder(Integer userId, Integer orderId, String reference,
                                              Instant paidAt, Instant observedAt) {
            effect("voucher", orderId, 0);
        }
        private void effect(String kind, Integer orderId, Integer itemId) {
            jdbc.update("INSERT INTO settlement_test_effects(kind, order_id, item_id) VALUES (?, ?, ?)",
                    kind, orderId, itemId);
            if (kind.equals(transientFailAt)) throw new TestTransientPersistenceFailure();
            if (kind.equals(failAt)) throw new IllegalStateException("Injected " + kind + " confirmation failure");
        }
    }

    private static final class TestTransientPersistenceFailure extends TransientDataAccessException {
        private TestTransientPersistenceFailure() { super("Injected transient participant failure"); }
    }
}
