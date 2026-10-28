package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.payment.vnpay.PaymentSessionApplicationService;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.payment.vnpay.enabled=true",
        "app.payment.vnpay.tmn-code=DEMOV210",
        "app.payment.vnpay.secret-key=integration-test-only-secret",
        "app.payment.vnpay.return-url=https://merchant.example.test/return",
        "app.payment.vnpay.ipn-url=https://merchant.example.test/ipn"
})
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(PaymentSessionJpaH2IntegrationTest.TestClockConfiguration.class)
class PaymentSessionJpaH2IntegrationTest {
    @Autowired private PaymentSessionApplicationService service;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private MutableClock testClock;
    private final PaymentPersistenceMapper attemptMapper = new PaymentPersistenceMapper();

    @BeforeEach void resetClock() { testClock.set(Instant.now().truncatedTo(ChronoUnit.MICROS)); }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean @Primary MutableClock paymentSessionTestClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<List<Instant>> values = new AtomicReference<>(List.of(Instant.EPOCH));
        void set(Instant instant) { values.set(List.of(instant)); }
        void setSequence(Instant first, Instant second) { values.set(List.of(first, second)); }
        @Override public Instant instant() {
            while (true) {
                var current = values.get();
                Instant result = current.getFirst();
                List<Instant> next = current.size() > 1 ? current.subList(1, current.size()) : current;
                if (values.compareAndSet(current, next)) return result;
            }
        }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    @Test void createsAndReusesPersistedAttemptAndUniqueReference() {
        int owner = 9101;
        int orderId = createOrder(owner, 12_345, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(3));
        var first = service.createOrReuse(owner, orderId, testClock.instant().minusSeconds(1), "127.0.0.1");
        var replay = service.createOrReuse(owner, orderId, testClock.instant(), "127.0.0.1");
        assertFalse(first.reused());
        assertTrue(replay.reused());
        assertEquals(first.paymentAttemptId(), replay.paymentAttemptId());
        assertEquals(first.merchantReference(), replay.merchantReference());
        assertTrue(first.merchantReference().matches("dca-[a-f0-9]{32}"));
        assertTrue(first.paymentUrl().contains("vnp_TxnRef=" + first.merchantReference()));
        assertTrue(first.toString().contains("paymentUrl=<redacted>"));
        assertEquals(1, attempts.findAllByOrderIdAndUserId(orderId, owner).size());
    }

    @Test void serializesConcurrentFirstRequestsOnTheOrderRow() throws Exception {
        int owner = 9102;
        int orderId = createOrder(owner, 50_000, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(2));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); var now = testClock.instant(); return service.createOrReuse(owner, orderId, now, "127.0.0.1"); });
            var right = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); var now = testClock.instant(); return service.createOrReuse(owner, orderId, now, "127.0.0.1"); });
            var first = left.get(12, TimeUnit.SECONDS);
            var second = right.get(12, TimeUnit.SECONDS);
            assertEquals(first.paymentAttemptId(), second.paymentAttemptId());
            assertEquals(1, attempts.findAllByOrderIdAndUserId(orderId, owner).size());
        }
    }

    @Test void rejectsWrongOwnerExpiredAndNonPendingOrders() {
        int owner = 9103;
        int orderId = createOrder(owner, 1000, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(2));
        assertThrows(java.util.NoSuchElementException.class,
                () -> service.createOrReuse(owner + 1, orderId, testClock.instant(), "127.0.0.1"));

        int expired = createOrder(owner, 1000, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(1900));
        assertThrows(IllegalStateException.class,
                () -> service.createOrReuse(owner, expired, testClock.instant(), "127.0.0.1"));

        int completed = createOrder(owner, 1000, OrderStatus.COMPLETED, testClock.instant().minusSeconds(10));
        assertThrows(IllegalStateException.class,
                () -> service.createOrReuse(owner, completed, testClock.instant(), "127.0.0.1"));
        assertTrue(attempts.findAllByOrderIdAndUserId(orderId, owner).isEmpty());
    }

    @Test void retriesOnlyConfirmedFailedAttemptAndRejectsSucceededOrUnknown() {
        int owner = 9104;
        int failedOrder = createOrder(owner, 1000, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(3));
        int failedId = persistAttempt(failedOrder, owner, PaymentStatus.FAILED);
        var retry = service.createOrReuse(owner, failedOrder, testClock.instant(), "127.0.0.1");
        assertFalse(retry.reused());
        assertNotEquals(failedId, retry.paymentAttemptId());
        assertEquals(2, attempts.findAllByOrderIdAndUserId(failedOrder, owner).size());

        int succeededOrder = createOrder(owner, 1000, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(3));
        persistAttempt(succeededOrder, owner, PaymentStatus.SUCCEEDED);
        assertThrows(IllegalStateException.class,
                () -> service.createOrReuse(owner, succeededOrder, testClock.instant(), "127.0.0.1"));

        int unknownOrder = createOrder(owner, 1000, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(3));
        persistAttempt(unknownOrder, owner, PaymentStatus.UNKNOWN);
        assertThrows(IllegalStateException.class,
                () -> service.createOrReuse(owner, unknownOrder, testClock.instant(), "127.0.0.1"));
    }

    @Test void signingFailureRollsBackNewAttempt() {
        int owner = 9105;
        int orderId = createOrder(owner, 1000, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(2));
        assertThrows(IllegalArgumentException.class,
                () -> service.createOrReuse(owner, orderId, testClock.instant(), "bad\r\n127.0.0.1"));
        assertTrue(attempts.findAllByOrderIdAndUserId(orderId, owner).isEmpty());
    }

    @Test void rejectsPersistedAttemptAmountMismatchWithoutCreatingAnotherAttempt() {
        int owner = 9106;
        int orderId = createOrder(owner, 1000, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(3));
        persistAttempt(orderId, owner, PaymentStatus.CREATED, 999L);
        assertThrows(IllegalArgumentException.class,
                () -> service.createOrReuse(owner, orderId, testClock.instant(), "127.0.0.1"));
        assertEquals(1, attempts.findAllByOrderIdAndUserId(orderId, owner).size());

        int oversized = createOrder(owner, 10_000_000_000L, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(3));
        assertThrows(IllegalStateException.class,
                () -> service.createOrReuse(owner, oversized, testClock.instant(), "127.0.0.1"));
        assertTrue(attempts.findAllByOrderIdAndUserId(oversized, owner).isEmpty());

        int zeroAmount = createOrder(owner, 0, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(3));
        assertThrows(IllegalStateException.class,
                () -> service.createOrReuse(owner, zeroAmount, testClock.instant(), "127.0.0.1"));
        assertTrue(attempts.findAllByOrderIdAndUserId(zeroAmount, owner).isEmpty());
    }

    @Test void reusesPendingAttemptAndDoesNotCreateForCancelledOrExpiredAttempts() {
        int owner = 9107;
        int pendingOrder = createOrder(owner, 1000, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(3));
        int pendingId = persistAttempt(pendingOrder, owner, PaymentStatus.PENDING);
        var pendingSession = service.createOrReuse(owner, pendingOrder, testClock.instant(), "127.0.0.1");
        assertTrue(pendingSession.reused());
        assertEquals(pendingId, pendingSession.paymentAttemptId());

        for (var terminal : List.of(PaymentStatus.CANCELLED, PaymentStatus.EXPIRED)) {
            int orderId = createOrder(owner, 1000, OrderStatus.PENDING_PAYMENT, testClock.instant().minusSeconds(3));
            persistAttempt(orderId, owner, terminal);
            assertThrows(IllegalStateException.class,
                    () -> service.createOrReuse(owner, orderId, testClock.instant(), "127.0.0.1"));
            assertEquals(1, attempts.findAllByOrderIdAndUserId(orderId, owner).size());
        }
    }

    @Test void rechecksDeadlineAfterLockWaitBeforeAttemptCreation() {
        int owner = 9108;
        Instant created = testClock.instant().minusSeconds(1800);
        int orderId = createOrder(owner, 1000, OrderStatus.PENDING_PAYMENT, created);
        var order = orders.findByIdAndUserId(orderId, owner).orElseThrow();
        Instant deadline = order.getPaymentExpiresAt();
        testClock.setSequence(deadline.minusNanos(1000), deadline);
        assertThrows(IllegalStateException.class,
                () -> service.createOrReuse(owner, orderId, deadline.minusNanos(1000), "127.0.0.1"));
        assertTrue(attempts.findAllByOrderIdAndUserId(orderId, owner).isEmpty());
    }

    private int createOrder(int owner, long amount, OrderStatus status, Instant createdAt) {
        Instant persistedCreatedAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        return new TransactionTemplate(transactionManager).execute(tx -> {
            var base = Order.create(owner, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 1,
                    "Payment session test", 1, amount, true)), 0, Optional.empty(),
                    Clock.fixed(persistedCreatedAt.plusSeconds(1), ZoneOffset.UTC));
            var saved = base;
            if (status != OrderStatus.PENDING_PAYMENT) {
                var snapshot = new Order.Snapshot(owner, base.getItems(), base.getVoucher(), "VND", status,
                        PaymentStatus.CREATED, base.getMerchandiseSubtotalVnd(), base.getEligibleSubtotalVnd(),
                        base.getDiscountVnd(), base.getShippingFeeVnd(), base.getTotalVnd());
                saved = Order.restore(snapshot, persistedCreatedAt, persistedCreatedAt.plusSeconds(1800));
            }
            var row = OrderJpaEntity.fromSnapshot(saved);
            entityManager.persist(row);
            entityManager.flush();
            return row.getId();
        });
    }

    private int persistAttempt(int orderId, int owner, PaymentStatus state) {
        return persistAttempt(orderId, owner, state, null);
    }

    private int persistAttempt(int orderId, int owner, PaymentStatus state, Long amountOverride) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            var orderRow = orders.findByIdAndUserId(orderId, owner).orElseThrow();
            var order = new com.drawingcourse.backend.service.OrderPersistenceMapper().toDomain(orderRow);
            var domain = PaymentAttempt.create(orderId, order, "vnpay", "fixture-" + UUID.randomUUID(),
                    Clock.fixed(orderRow.getCreatedAt().plusMillis(1), ZoneOffset.UTC));
            if (amountOverride != null) domain = new PaymentAttempt(null, domain.orderId(), domain.userId(), domain.provider(),
                    domain.merchantReference(), amountOverride, domain.currency(), domain.orderCreatedAt(), domain.expiresAt(),
                    domain.createdAt(), domain.updatedAt(), domain.status(), null, null, PaymentAttempt.ReviewReason.NONE);
            var row = PaymentAttemptJpaEntity.newSnapshot(domain);
            entityManager.persist(row);
            entityManager.flush();
            if (state != PaymentStatus.CREATED) {
                var persisted = attemptMapper.toDomain(row);
                var updateTime = persisted.createdAt().plusMillis(1);
                var changed = new PaymentAttempt(row.getId(), persisted.orderId(), persisted.userId(), persisted.provider(),
                        persisted.merchantReference(), persisted.expectedAmountVnd(), persisted.currency(), persisted.orderCreatedAt(),
                        persisted.expiresAt(), persisted.createdAt(), updateTime, state,
                        state == PaymentStatus.SUCCEEDED ? "gateway-fixture-transaction" : null,
                        state == PaymentStatus.SUCCEEDED ? updateTime : null, PaymentAttempt.ReviewReason.NONE);
                row.applyDecision(changed);
                entityManager.flush();
            }
            return row.getId();
        });
    }
}
