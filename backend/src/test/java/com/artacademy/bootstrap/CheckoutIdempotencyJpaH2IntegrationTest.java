package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.CartJpaEntity;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderJpaEntity;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.repository.CheckoutRequestRepository;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.service.checkout.CheckoutIdempotencyService;
import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class CheckoutIdempotencyJpaH2IntegrationTest {
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository users;
    @Autowired private CheckoutRequestRepository requests;
    @Autowired private OrderRepository orders;
    @Autowired private PlatformTransactionManager transactionManager;

    private CheckoutIdempotencyService idempotency;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        idempotency = new CheckoutIdempotencyService(requests, users, orders,
                Clock.fixed(Instant.parse("2026-10-28T01:02:03.123456Z"), ZoneOffset.UTC),
                transactionManager);
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void firstClaimThenSameFingerprintIsInProgressAndKeysAreOwnerScoped() {
        Integer owner = createUser();
        Integer other = createUser();
        String fingerprint = request(4).canonicalFingerprint();

        var first = inTx(() -> idempotency.claim(owner, "checkout-key-1", fingerprint));
        var duplicate = inTx(() -> idempotency.claim(owner, "checkout-key-1", fingerprint));
        var otherOwner = inTx(() -> idempotency.claim(other, "checkout-key-1", fingerprint));

        assertThat(first).isInstanceOf(CheckoutIdempotencyService.Claimed.class);
        assertThat(duplicate).isInstanceOf(CheckoutIdempotencyService.InProgress.class);
        assertThat(otherOwner).isInstanceOf(CheckoutIdempotencyService.Claimed.class);
        assertThrows(IllegalStateException.class, () -> idempotency.claim(owner, "outside-tx", fingerprint));
    }

    @Test
    void fingerprintMismatchReturnsConflictWithoutChangingOriginalClaim() {
        Integer owner = createUser();
        String key = uniqueKey();
        String original = request(5).canonicalFingerprint();
        String changed = request(6).canonicalFingerprint();

        inTx(() -> idempotency.claim(owner, key, original));
        var result = inTx(() -> idempotency.claim(owner, key, changed));

        assertThat(result).isInstanceOf(CheckoutIdempotencyService.FingerprintConflict.class);
    }

    @Test
    void completedRequestReplaysOriginalOrderBeforeAnyCurrentCartRevisionCheck() {
        Integer owner = createUser();
        String key = uniqueKey();
        String originalFingerprint = request(7).canonicalFingerprint();
        Integer[] created = inTx(() -> {
            var claim = (CheckoutIdempotencyService.Claimed) idempotency.claim(owner, key, originalFingerprint);
            Integer orderId = persistOrder(owner);
            idempotency.complete(owner, claim.requestId(), orderId);
            var cart = new CartJpaEntity(owner);
            entityManager.persist(cart);
            for (int revision = 0; revision < 7; revision++) cart.incrementRevision();
            cart.incrementRevision(); // successful Checkout clears/changes the Cart after Order creation
            entityManager.flush();
            return new Integer[]{claim.requestId(), orderId};
        });

        long currentCartRevision = inTx(() -> entityManager.createQuery(
                "select c.revision from CartJpaEntity c where c.userId = :owner", Long.class)
                .setParameter("owner", owner).getSingleResult());
        assertEquals(8L, currentCartRevision);
        // Replay uses the original fingerprint despite a later persisted Cart revision.
        var replay = inTx(() -> idempotency.claim(owner, key, originalFingerprint));
        assertThat(replay).isEqualTo(new CheckoutIdempotencyService.Replayed(created[1]));
    }

    @Test
    void orderAndCompletedIdempotencyRecordRollBackTogether() {
        Integer owner = createUser();
        String key = uniqueKey();
        String fingerprint = request(8).canonicalFingerprint();

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            var claim = (CheckoutIdempotencyService.Claimed) idempotency.claim(owner, key, fingerprint);
            persistOrder(owner);
            idempotency.complete(owner, claim.requestId(), latestOrderId(owner));
            throw new IllegalStateException("force transaction rollback");
        }));

        Long claims = inTx(() -> entityManager.createQuery(
                "select count(r) from CheckoutRequestJpaEntity r where r.user.id = :owner and r.idempotencyKey = :key", Long.class)
                .setParameter("owner", owner).setParameter("key", key).getSingleResult());
        Long orderCount = inTx(() -> entityManager.createQuery(
                "select count(o) from OrderJpaEntity o where o.userId = :owner", Long.class)
                .setParameter("owner", owner).getSingleResult());
        assertEquals(0L, claims);
        assertEquals(0L, orderCount);
    }

    @Test
    void concurrentDuplicateClaimsCreateAtMostOneProcessingRow() throws Exception {
        Integer owner = createUser();
        String key = uniqueKey();
        String fingerprint = request(9).canonicalFingerprint();
        var gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> concurrentClaim(owner, key, fingerprint, gate));
            Future<Object> second = pool.submit(() -> concurrentClaim(owner, key, fingerprint, gate));
            gate.countDown();
            Object a = first.get();
            Object b = second.get();
            assertThat(List.of(a, b)).filteredOn(CheckoutIdempotencyService.Claimed.class::isInstance).hasSize(1);
            assertThat(List.of(a, b)).anyMatch(value -> value instanceof CheckoutIdempotencyService.InProgress
                    || value instanceof CheckoutIdempotencyService.ConcurrentClaimException);
        } finally {
            pool.shutdownNow();
        }
        Long count = inTx(() -> entityManager.createQuery(
                "select count(r) from CheckoutRequestJpaEntity r where r.user.id = :owner and r.idempotencyKey = :key", Long.class)
                .setParameter("owner", owner).setParameter("key", key).getSingleResult());
        assertEquals(1L, count);
    }

    private Object concurrentClaim(Integer owner, String key, String fingerprint, CountDownLatch gate) throws Exception {
        gate.await();
        try {
            return inTx(() -> idempotency.claim(owner, key, fingerprint));
        } catch (CheckoutIdempotencyService.ConcurrentClaimException expectedRace) {
            return expectedRace;
        }
    }

    private Integer createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return inTx(() -> users.saveAndFlush(User.builder().fullName("Checkout test")
                .email("checkout-" + suffix + "@example.test").passwordHash("test-hash")
                .role(Role.USER).isActive(true).build()).getId());
    }

    private Integer persistOrder(Integer owner) {
        var snapshot = Order.create(owner, List.of(new OrderLineSnapshot(OrderItemType.COURSE, 12001,
                "Test course snapshot", 1, 10_000, true)), 0, Optional.empty(),
                Clock.fixed(Instant.parse("2026-10-28T01:02:03.123456Z"), ZoneOffset.UTC));
        var row = OrderJpaEntity.fromSnapshot(snapshot);
        entityManager.persist(row);
        entityManager.flush();
        return row.getId();
    }

    private Integer latestOrderId(Integer owner) {
        return entityManager.createQuery("select o.id from OrderJpaEntity o where o.userId = :owner order by o.id desc", Integer.class)
                .setParameter("owner", owner).setMaxResults(1).getSingleResult();
    }

    private CheckoutRequest request(long revision) {
        return new CheckoutRequest(revision, List.of(new CheckoutRequest.Selection(1, 1)), Optional.empty(), Optional.empty());
    }

    private <T> T inTx(Supplier<T> work) { return tx.execute(status -> work.get()); }
    private static String uniqueKey() { return "ck-" + UUID.randomUUID(); }
}
