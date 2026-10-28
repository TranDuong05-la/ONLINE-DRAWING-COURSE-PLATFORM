package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.payment.vnpay.IpnClassification;
import com.artacademy.payment.vnpay.IpnReconciliationResult;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import com.drawingcourse.backend.service.settlement.*;
import com.drawingcourse.backend.service.expiration.*;
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

/** Exercises expiry coordination and rollback against the isolated in-memory H2 profile. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class OrderExpirationCoordinatorJpaH2IntegrationTest {
    private static final int USER_ID = 7601;
    private static final Instant CREATED = Instant.parse("2026-10-28T03:00:00Z");
    private static final Instant PAID = CREATED.plusSeconds(10);
    private static final Instant RECEIVED = CREATED.plusSeconds(11);
    private static final Instant DEADLINE = CREATED.plusSeconds(30 * 60L);
    private static final Instant AT_DEADLINE = DEADLINE;

    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentEventRepository events;
    @Autowired private PaymentEventConflictRepository conflicts;
    @Autowired private PaymentSettlementRepository settlements;
    @Autowired private JpaSettlementLedgerAdapter jpaLedger;
    @Autowired private com.drawingcourse.backend.service.checkout.JpaVoucherReservationService jpaVouchers;

    private TestReleaseParticipants releases;
    private OrderExpirationCoordinator expirer;

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS expiration_test_effects (kind VARCHAR(32) NOT NULL, order_id INT NOT NULL, item_id INT NOT NULL)");
        jdbc.update("DELETE FROM expiration_test_effects");
        releases = new TestReleaseParticipants(jdbc);
        expirer = expirer(releases);
    }

    @Test
    void productionExpirationCoordinatorIsNotRegisteredAsSchedulerOrBean() {
        assertTrue(applicationContext.getBeansOfType(OrderExpirationCoordinator.class).isEmpty());
    }

    @Test
    void expirationBeforeExclusiveDeadlineIsNotDue() {
        var fixture = fixture(PaymentStatus.CREATED, null, null, false);
        var result = expirer(fixed(CREATED.plusSeconds(1799))).expire(fixture.orderId());

        assertEquals(ExpirationResult.Outcome.NOT_DUE, result.outcome());
        assertPending(fixture);
    }

    @Test
    void expirationAtDeadlineReleasesEachLocalReservationOnceAndReplayIsIdempotent() {
        var fixture = fixture(PaymentStatus.CREATED, null, null, false);
        var coordinator = expirer(fixed(AT_DEADLINE));

        var first = coordinator.expire(fixture.orderId());
        var repeated = coordinator.expire(fixture.orderId());

        assertEquals(ExpirationResult.Outcome.EXPIRED, first.outcome());
        assertEquals(ExpirationResult.Outcome.ALREADY_EXPIRED, repeated.outcome());
        var expired = order(fixture);
        assertEquals(OrderStatus.EXPIRED, expired.getStatus());
        assertEquals(PaymentStatus.EXPIRED, expired.getPaymentStatus());
        assertEquals(1, effects("voucher", fixture.orderId()));
        assertEquals(1, effects("inventory", fixture.orderId()));
        assertEquals(1, effects("course", fixture.orderId()));
    }

    @Test
    void realJpaVoucherReleaseParticipantJoinsExpirationTransaction() {
        var fixture = fixture(PaymentStatus.CREATED, null, null, false);
        var coordinator = new OrderExpirationCoordinator(orders, attempts, events, conflicts, settlements,
                jpaVouchers, releases, releases, fixed(AT_DEADLINE), transactionManager);

        var result = coordinator.expire(fixture.orderId());

        assertEquals(ExpirationResult.Outcome.EXPIRED, result.outcome());
        assertEquals(OrderStatus.EXPIRED, order(fixture).getStatus());
        assertEquals(1, effects("inventory", fixture.orderId()));
        assertEquals(1, effects("course", fixture.orderId()));
    }

    @Test
    void confirmedOrderCannotExpire() {
        var fixture = fixture(PaymentStatus.SUCCEEDED, null, null, false, OrderStatus.CONFIRMED);

        var result = expirer(fixed(AT_DEADLINE)).expire(fixture.orderId());

        assertEquals(ExpirationResult.Outcome.NOT_ELIGIBLE, result.outcome());
        assertEquals(OrderStatus.CONFIRMED, order(fixture).getStatus());
        assertNoReleaseEffects(fixture);
    }

    @Test
    void existingSettlementBlocksExpirationAndRequiresReview() {
        var fixture = fixture(PaymentStatus.SUCCEEDED, PaymentEvent.Outcome.SUCCEEDED,
                PaymentEventAudit.Disposition.APPLIED, false);
        tx().executeWithoutResult(status -> entityManager.persist(PaymentSettlementJpaEntity.record(
                fixture.orderId(), fixture.attemptId(), fixture.eventId(), PAID, AT_DEADLINE)));

        var result = expirer(fixed(AT_DEADLINE)).expire(fixture.orderId());

        assertEquals(ExpirationResult.Outcome.REQUIRES_REVIEW, result.outcome());
        assertPendingWithoutLedgerCheck(fixture);
        assertNoReleaseEffects(fixture);
    }

    @Test
    void providerPendingAttemptIsUnresolvedButCleanFailedAttemptCanExpire() {
        var pending = fixture(PaymentStatus.PENDING, null, null, false);
        var pendingResult = expirer(fixed(AT_DEADLINE)).expire(pending.orderId());
        assertEquals(ExpirationResult.Outcome.REQUIRES_REVIEW, pendingResult.outcome());
        assertPending(pending);

        var failed = fixture(PaymentStatus.FAILED, null, null, false);
        var failedResult = expirer(fixed(AT_DEADLINE)).expire(failed.orderId());
        assertEquals(ExpirationResult.Outcome.EXPIRED, failedResult.outcome());
        assertEquals(OrderStatus.EXPIRED, order(failed).getStatus());
    }

    @Test
    void unknownAndSuccessfulPaymentEvidenceRequireReview() {
        var unknown = fixture(PaymentStatus.UNKNOWN, PaymentEvent.Outcome.UNKNOWN,
                PaymentEventAudit.Disposition.APPLIED, false);
        assertEquals(ExpirationResult.Outcome.REQUIRES_REVIEW,
                expirer(fixed(AT_DEADLINE)).expire(unknown.orderId()).outcome());
        assertPending(unknown);

        var success = fixture(PaymentStatus.SUCCEEDED, PaymentEvent.Outcome.SUCCEEDED,
                PaymentEventAudit.Disposition.APPLIED, false);
        assertEquals(ExpirationResult.Outcome.REQUIRES_REVIEW,
                expirer(fixed(AT_DEADLINE)).expire(success.orderId()).outcome());
        assertPending(success);
    }

    @Test
    void conflictingPaymentEvidenceRequiresReview() {
        var fixture = fixture(PaymentStatus.FAILED, PaymentEvent.Outcome.FAILED,
                PaymentEventAudit.Disposition.APPLIED, false);
        tx().executeWithoutResult(status -> {
            var first = events.findById(fixture.eventId()).orElseThrow();
            var mapper = new PaymentPersistenceMapper();
            var attempt = mapper.toDomain(attempts.findByIdAndUserId(fixture.attemptId(), USER_ID).orElseThrow());
            var conflictEvent = new PaymentEvent(first.getProvider(), first.getEventReference(),
                    first.getMerchantReference(), first.getTransactionReference(), first.getAmountVnd(),
                    first.getCurrency(), PaymentEvent.Outcome.SUCCEEDED, PAID, RECEIVED, "e".repeat(64),
                    "vnpay-hmac-sha512;paydate=valid");
            var conflicting = PaymentEventConflictJpaEntity.verifiedConflict(mapper.toDomain(first),
                    conflictEvent, attempt, OrderStatus.PENDING_PAYMENT);
            entityManager.persist(conflicting);
            entityManager.flush();
        });

        var result = expirer(fixed(AT_DEADLINE)).expire(fixture.orderId());

        assertEquals(ExpirationResult.Outcome.REQUIRES_REVIEW, result.outcome());
        assertPending(fixture);
    }

    @Test
    void concurrentSettlementAndExpirationCannotBothApply() throws Exception {
        var fixture = fixture(PaymentStatus.SUCCEEDED, PaymentEvent.Outcome.SUCCEEDED,
                PaymentEventAudit.Disposition.APPLIED, false);
        var settlementPorts = new TestSettlementParticipants(jdbc);
        var settlement = new SettlementCoordinator(orders, attempts, events, jpaLedger, settlementPorts,
                settlementPorts, settlementPorts, fixed(PAID.plusSeconds(2)), transactionManager);
        var expire = expirer(fixed(AT_DEADLINE));
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var settleFuture = executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                return settlement.settle(fixture.validSuccessResult());
            });
            var expireFuture = executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                return expire.expire(fixture.orderId());
            });
            start.countDown();
            var settlementResult = settleFuture.get(8, TimeUnit.SECONDS);
            var expirationResult = expireFuture.get(8, TimeUnit.SECONDS);

            assertEquals(SettlementResult.Outcome.SETTLED, settlementResult.outcome());
            assertTrue(expirationResult.outcome() == ExpirationResult.Outcome.REQUIRES_REVIEW
                    || expirationResult.outcome() == ExpirationResult.Outcome.NOT_ELIGIBLE);
            assertEquals(OrderStatus.CONFIRMED, order(fixture).getStatus());
            assertTrue(settlements.findByOrderId(fixture.orderId()).isPresent());
            assertEquals(0, effects("voucher", fixture.orderId()) + effects("inventory", fixture.orderId())
                    + effects("course", fixture.orderId()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void voucherReleaseFailureRollsBackOrderAndAllReleaseEffects() {
        releases.failAt = "voucher";
        var fixture = fixture(PaymentStatus.CREATED, null, null, false);

        assertThrows(IllegalStateException.class, () -> expirer(fixed(AT_DEADLINE)).expire(fixture.orderId()));

        assertPending(fixture);
    }

    @Test
    void inventoryReleaseFailureRollsBackOrderVoucherAndAllReleaseEffects() {
        releases.failAt = "inventory";
        var fixture = fixture(PaymentStatus.CREATED, null, null, false);

        assertThrows(IllegalStateException.class, () -> expirer(fixed(AT_DEADLINE)).expire(fixture.orderId()));

        assertPending(fixture);
    }

    @Test
    void courseReleaseFailureRollsBackOrderVoucherInventoryAndAllReleaseEffects() {
        releases.failAt = "course";
        var fixture = fixture(PaymentStatus.CREATED, null, null, false);

        assertThrows(IllegalStateException.class, () -> expirer(fixed(AT_DEADLINE)).expire(fixture.orderId()));

        assertPending(fixture);
    }

    @Test
    void retryableFailureIsReturnedOnlyAfterCoordinatorOwnedRollback() {
        releases.transientFailAt = "voucher";
        var fixture = fixture(PaymentStatus.CREATED, null, null, false);

        var result = expirer(fixed(AT_DEADLINE)).expire(fixture.orderId());

        assertEquals(ExpirationResult.Outcome.RETRYABLE_FAILURE, result.outcome());
        assertPending(fixture);
    }

    @Test
    void transientFailureInsideOuterTransactionPropagatesUntilOuterRollback() {
        releases.transientFailAt = "voucher";
        var fixture = fixture(PaymentStatus.CREATED, null, null, false);

        assertThrows(TestTransientReleaseFailure.class,
                () -> tx().executeWithoutResult(status -> expirer(fixed(AT_DEADLINE)).expire(fixture.orderId())));

        assertPending(fixture);
    }

    private OrderExpirationCoordinator expirer(TestReleaseParticipants ports) {
        return new OrderExpirationCoordinator(orders, attempts, events, conflicts, settlements,
                ports, ports, ports, fixed(AT_DEADLINE), transactionManager);
    }

    private OrderExpirationCoordinator expirer(Clock clock) {
        return new OrderExpirationCoordinator(orders, attempts, events, conflicts, settlements,
                releases, releases, releases, clock, transactionManager);
    }

    private Fixture fixture(PaymentStatus attemptStatus, PaymentEvent.Outcome eventOutcome,
                            PaymentEventAudit.Disposition disposition, boolean unused) {
        return fixture(attemptStatus, eventOutcome, disposition, unused, OrderStatus.PENDING_PAYMENT);
    }

    private Fixture fixture(PaymentStatus attemptStatus, PaymentEvent.Outcome eventOutcome,
                            PaymentEventAudit.Disposition disposition, boolean unused, OrderStatus orderStatus) {
        return tx().execute(status -> {
            var initial = Order.create(USER_ID,
                    List.of(new OrderLineSnapshot(OrderItemType.COURSE, 91, "Expiry course", 1, 1000, true),
                            new OrderLineSnapshot(OrderItemType.PRODUCT, 92, "Expiry product", 1, 2000, true)),
                    0, Optional.empty(), Clock.fixed(CREATED, ZoneOffset.UTC));
            Order domain = initial;
            if (orderStatus != OrderStatus.PENDING_PAYMENT) {
                var snapshot = new Order.Snapshot(initial.getUserId(), initial.getItems(), initial.getVoucher(), "VND",
                        OrderStatus.CONFIRMED, PaymentStatus.SUCCEEDED, initial.getMerchandiseSubtotalVnd(),
                        initial.getEligibleSubtotalVnd(), initial.getDiscountVnd(), initial.getShippingFeeVnd(), initial.getTotalVnd());
                domain = Order.restore(snapshot, initial.getCreatedAt(), initial.getExpiresAt());
            }
            var orderRow = OrderJpaEntity.fromSnapshot(domain);
            entityManager.persist(orderRow);
            entityManager.flush();

            Integer attemptId = null;
            Integer eventId = null;
            if (attemptStatus != null) {
                String merchant = "expiration-merchant-" + orderRow.getId();
                String transactionReference = attemptStatus == PaymentStatus.SUCCEEDED ? "expiration-txn-" + orderRow.getId() : null;
                Instant attemptCreated = CREATED.plusSeconds(2);
                Instant succeededAt = attemptStatus == PaymentStatus.SUCCEEDED ? PAID : null;
                var attempt = new PaymentAttempt(null, orderRow.getId(), USER_ID, "vnpay", merchant,
                        orderRow.getTotalVnd(), "VND", CREATED, DEADLINE, attemptCreated, RECEIVED,
                        attemptStatus, transactionReference, succeededAt, PaymentAttempt.ReviewReason.NONE);
                var attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
                entityManager.persist(attemptRow);
                entityManager.flush();
                attemptId = attemptRow.getId();

                if (eventOutcome != null) {
                    if (transactionReference == null) transactionReference = "expiration-txn-" + orderRow.getId();
                    var event = new PaymentEvent("vnpay", merchant + ":" + transactionReference, merchant,
                            transactionReference, orderRow.getTotalVnd(), "VND", eventOutcome,
                            eventOutcome == PaymentEvent.Outcome.SUCCEEDED ? PAID : attemptCreated.plusSeconds(1),
                            RECEIVED, "f".repeat(64), "vnpay-hmac-sha512;paydate=valid");
                    PaymentStatus after = switch (attemptStatus) {
                        case SUCCEEDED -> PaymentStatus.SUCCEEDED;
                        case UNKNOWN -> PaymentStatus.UNKNOWN;
                        case PENDING -> PaymentStatus.PENDING;
                        default -> PaymentStatus.FAILED;
                    };
                    var audit = new PaymentEventAudit(null, attemptRow.getId(), orderRow.getId(), USER_ID,
                            event, event.fingerprint(), PaymentStatus.CREATED, after, disposition,
                            PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
                    var eventRow = PaymentEventJpaEntity.newSnapshot(audit);
                    entityManager.persist(eventRow);
                    entityManager.flush();
                    eventId = eventRow.getId();
                }
            }
            return new Fixture(orderRow.getId(), attemptId, eventId);
        });
    }

    private OrderJpaEntity order(Fixture fixture) {
        return tx().execute(status -> orders.findByIdAndUserId(fixture.orderId(), USER_ID).orElseThrow());
    }

    private void assertPending(Fixture fixture) {
        assertPendingWithoutLedgerCheck(fixture);
        assertTrue(settlements.findByOrderId(fixture.orderId()).isEmpty());
        assertNoReleaseEffects(fixture);
    }

    private void assertPendingWithoutLedgerCheck(Fixture fixture) {
        var order = order(fixture);
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        assertEquals(PaymentStatus.CREATED, order.getPaymentStatus());
    }

    private void assertNoReleaseEffects(Fixture fixture) {
        assertEquals(0, effects("voucher", fixture.orderId()) + effects("inventory", fixture.orderId())
                + effects("course", fixture.orderId()));
    }

    private int effects(String kind, Integer orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM expiration_test_effects WHERE kind=? AND order_id=?",
                Integer.class, kind, orderId);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private Clock fixed(Instant instant) { return Clock.fixed(instant, ZoneOffset.UTC); }

    private record Fixture(Integer orderId, Integer attemptId, Integer eventId) {
        IpnReconciliationResult validSuccessResult() {
            return new IpnReconciliationResult(IpnClassification.VALID_ON_TIME_SUCCESS, attemptId, orderId,
                    eventId, null, PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE, false);
        }
    }

    private static final class TestReleaseParticipants implements VoucherExpirationReleasePort,
            InventoryReservationReleasePort, CoursePurchaseReleasePort {
        private final JdbcTemplate jdbc;
        private volatile String failAt;
        private volatile String transientFailAt;
        private TestReleaseParticipants(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override public void releaseVoucherForExpiredOrder(Integer userId, Integer orderId, Instant at) {
            effect("voucher", orderId, 0);
        }
        @Override public void releaseProductForExpiredOrder(Integer userId, Integer productId, Integer orderId, Instant at) {
            effect("inventory", orderId, productId);
        }
        @Override public void releaseCourseForExpiredOrder(Integer userId, Integer courseId, Integer orderId, Instant at) {
            effect("course", orderId, courseId);
        }
        private void effect(String kind, Integer orderId, Integer itemId) {
            jdbc.update("INSERT INTO expiration_test_effects(kind, order_id, item_id) VALUES (?, ?, ?)", kind, orderId, itemId);
            if (kind.equals(transientFailAt)) throw new TestTransientReleaseFailure();
            if (kind.equals(failAt)) throw new IllegalStateException("Injected " + kind + " release failure");
        }
    }

    private static final class TestTransientReleaseFailure extends TransientDataAccessException {
        private TestTransientReleaseFailure() { super("Injected transient release failure"); }
    }

    private static final class TestSettlementParticipants implements CourseOwnershipConfirmationPort,
            InventoryReservationConfirmationPort, VoucherConsumptionPort {
        private final JdbcTemplate jdbc;
        private TestSettlementParticipants(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public void confirmCourseForOrder(Integer userId, Integer courseId, Integer orderId, Instant at) {
            marker("confirm-course", orderId, courseId);
        }
        @Override public void confirmProductForOrder(Integer userId, Integer productId, Integer orderId, Instant at) {
            marker("confirm-inventory", orderId, productId);
        }
        @Override public void consumeForOrder(Integer userId, Integer orderId, String reference, Instant paidAt, Instant at) {
            marker("confirm-voucher", orderId, 0);
        }
        private void marker(String kind, Integer orderId, Integer itemId) {
            jdbc.update("INSERT INTO expiration_test_effects(kind, order_id, item_id) VALUES (?, ?, ?)", kind, orderId, itemId);
        }
    }
}
