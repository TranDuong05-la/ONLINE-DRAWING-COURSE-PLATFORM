package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.OrderPersistenceService;
import com.drawingcourse.backend.service.StoredOrder;
import com.drawingcourse.backend.service.checkout.CheckoutCoordinator;
import com.drawingcourse.backend.service.checkout.CheckoutIdempotencyPort;
import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import com.drawingcourse.backend.service.checkout.CourseCatalogPort;
import com.drawingcourse.backend.service.checkout.CoursePurchasePort;
import com.drawingcourse.backend.service.checkout.InventoryReservationPort;
import com.drawingcourse.backend.service.checkout.JpaCheckoutCartPort;
import com.drawingcourse.backend.service.checkout.OrderPersistenceCheckoutPort;
import com.drawingcourse.backend.service.checkout.ProductCatalogPort;
import com.drawingcourse.backend.service.checkout.ShippingQuotePort;
import com.drawingcourse.backend.service.checkout.CheckoutIdempotencyService;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.checkout.VoucherReservationPort;
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
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring Data/JPA and H2 row-lock/counter tests; no MySQL and no payment settlement implementation. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class VoucherReservationH2IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-28T05:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private VoucherRepository vouchers;
    @Autowired private VoucherUserUsageRepository usages;
    @Autowired private VoucherClaimRepository claims;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private CartRepository carts;
    @Autowired private CheckoutRequestRepository checkoutRequests;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JpaVoucherReservationService reservations;

    @Test
    void validReservationSnapshotsDefinitionAndIncrementsCountersOnceForSameOrderRetry() {
        Fixture f = fixture(voucher(code(), true, NOW.minusSeconds(60), NOW.plusSeconds(3600), 5, 2), "valid");
        inTx(() -> {
            var quote = reservations.quoteAndLock(f.userId, f.order.getVoucher().orElseThrow().code(), 10_000, NOW).orElseThrow();
            reservations.reserveForOrder(f.userId, f.stored, f.order, quote, NOW);
            reservations.reserveForOrder(f.userId, f.stored, f.order, quote, NOW);
        });
        assertEquals(1, count("voucher_claims", f.orderId));
        assertCounters(f.voucherId, f.userId, 1, 0, 1, 0);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            var claim = claims.findByOrderIdAndUserId(f.orderId, f.userId).orElseThrow();
            assertEquals(VoucherClaim.State.RESERVED, claim.getState());
            assertEquals(f.order.getExpiresAt(), claim.getExpiresAt());
            assertEquals(1L, claim.getDefinitionVersion());
        });
    }

    @Test
    void inactiveExpiredAndUsageLimitedVouchersAreRejected() {
        Fixture inactive = fixture(voucher(code(), false, NOW.minusSeconds(5), NOW.plusSeconds(5), 10, 10), "inactive");
        Fixture expired = fixture(voucher(code(), true, NOW.minusSeconds(10), NOW, 10, 10), "expired");
        Fixture limited = fixture(voucher(code(), true, NOW.minusSeconds(5), NOW.plusSeconds(5), 1, 1), "limited");
        Fixture perUserLimited = fixture(voucher(code(), true, NOW.minusSeconds(5), NOW.plusSeconds(5), 5, 1), "per-user-limited");
        inTx(() -> {
            VoucherJpaEntity row = vouchers.findByIdForUpdate(limited.voucherId).orElseThrow();
            row.reserveOne();
            vouchers.saveAndFlush(row);
            VoucherJpaEntity userLimitedRow = vouchers.findByIdForUpdate(perUserLimited.voucherId).orElseThrow();
            userLimitedRow.reserveOne();
            vouchers.saveAndFlush(userLimitedRow);
            VoucherUserUsageJpaEntity usage = new VoucherUserUsageJpaEntity(perUserLimited.voucherId, perUserLimited.userId);
            usage.reserveOne();
            usages.saveAndFlush(usage);
            assertThat(reservations.quoteAndLock(inactive.userId, inactive.order.getVoucher().orElseThrow().code(), 10_000, NOW)).isEmpty();
            assertThat(reservations.quoteAndLock(expired.userId, expired.order.getVoucher().orElseThrow().code(), 10_000, NOW)).isEmpty();
            assertThat(reservations.quoteAndLock(limited.userId, limited.order.getVoucher().orElseThrow().code(), 10_000, NOW)).isEmpty();
            assertThat(reservations.quoteAndLock(perUserLimited.userId,
                    perUserLimited.order.getVoucher().orElseThrow().code(), 10_000, NOW)).isEmpty();
        });
    }

    @Test
    void concurrentReservationsCannotOversubscribeTotalLimit() throws Exception {
        Voucher definition = voucher(code(), true, NOW.minusSeconds(5), NOW.plusSeconds(600), 1, 1);
        Fixture first = fixture(definition, "race-a");
        Fixture second = fixtureForExistingVoucher(first.voucherId, definition, "race-b");
        CountDownLatch firstHasVoucherLock = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondIsAttempting = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstTask = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var quote = reservations.quoteAndLock(first.userId,
                        first.order.getVoucher().orElseThrow().code(), 10_000, NOW).orElseThrow();
                firstHasVoucherLock.countDown();
                awaitLatch(allowFirstCommit);
                reservations.reserveForOrder(first.userId, first.stored, first.order, quote, NOW);
                return true;
            }));
            assertTrue(firstHasVoucherLock.await(5, TimeUnit.SECONDS));

            var secondTask = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                secondIsAttempting.countDown();
                var quote = reservations.quoteAndLock(second.userId,
                        second.order.getVoucher().orElseThrow().code(), 10_000, NOW);
                quote.ifPresent(value -> reservations.reserveForOrder(second.userId, second.stored, second.order, value, NOW));
                return quote.isPresent();
            }));
            assertTrue(secondIsAttempting.await(5, TimeUnit.SECONDS));
            try {
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> secondTask.get(200, TimeUnit.MILLISECONDS),
                        "second transaction must wait while the first holds the voucher definition lock");
            } finally {
                allowFirstCommit.countDown();
            }
            assertTrue(firstTask.get(10, TimeUnit.SECONDS));
            assertFalse(secondTask.get(10, TimeUnit.SECONDS), "losing reservation observes the committed quota");
        }
        assertEquals(1L, jdbc.queryForObject("select reserved_count from vouchers where id=?", Long.class, first.voucherId));
        assertEquals(1, jdbc.queryForObject("select count(*) from voucher_claims where voucher_id=?", Integer.class, first.voucherId));
    }

    @Test
    void cancellationAndExpirationReleaseReservationsExactlyOnce() {
        Fixture cancelled = reservedFixture("cancel");
        inTx(() -> {
            jdbc.update("update orders set status='CANCELLED', payment_status='CANCELLED' where id=?", cancelled.orderId);
            em.clear();
            reservations.releaseForOrder(cancelled.userId, cancelled.orderId, VoucherClaim.ReleaseReason.CANCELLED, NOW.plusSeconds(1));
            reservations.releaseForOrder(cancelled.userId, cancelled.orderId, VoucherClaim.ReleaseReason.CANCELLED, NOW.plusSeconds(2));
        });
        assertCounters(cancelled.voucherId, cancelled.userId, 0, 0, 0, 0);

        Fixture expired = reservedFixture("expire");
        inTx(() -> {
            OrderJpaEntity order = orders.findForUpdate(expired.orderId, expired.userId).orElseThrow();
            order.expireUnpaid(order.getPaymentExpiresAt());
            em.flush();
            reservations.releaseForOrder(expired.userId, expired.orderId, VoucherClaim.ReleaseReason.EXPIRED,
                    expired.order.getExpiresAt());
            reservations.releaseForOrder(expired.userId, expired.orderId, VoucherClaim.ReleaseReason.EXPIRED,
                    expired.order.getExpiresAt());
        });
        assertCounters(expired.voucherId, expired.userId, 0, 0, 0, 0);
    }

    @Test
    void retryableFailedPaymentCannotReleaseButFinalCancelledOrderCan() {
        Fixture f = reservedFixture("retryable");
        assertThrows(IllegalStateException.class, () -> inTx(() -> {
            jdbc.update("update orders set payment_status='FAILED' where id=?", f.orderId);
            em.clear();
            reservations.releaseForOrder(f.userId, f.orderId, VoucherClaim.ReleaseReason.CANCELLED, NOW.plusSeconds(1));
        }));
        assertCounters(f.voucherId, f.userId, 1, 0, 1, 0);
    }

    @Test
    void timelyVerifiedSuccessConsumesOnceEvenAfterVoucherIsDeactivated() {
        Fixture f = fixture(voucher(code(), true, NOW.minusSeconds(5), NOW.plusSeconds(5), 5, 5), "consume");
        VoucherReservationPort.Quote[] initialQuote = new VoucherReservationPort.Quote[1];
        inTx(() -> {
            initialQuote[0] = reservations.quoteAndLock(f.userId,
                    f.order.getVoucher().orElseThrow().code(), 10_000, NOW).orElseThrow();
            reservations.reserveForOrder(f.userId, f.stored, f.order, initialQuote[0], NOW);
        });
        inTx(() -> {
            VoucherJpaEntity definition = vouchers.findByIdForUpdate(f.voucherId).orElseThrow();
            Voucher domain = new com.drawingcourse.backend.service.VoucherPersistenceMapper().toDomain(definition);
            definition.replaceDefinition(domain.withActive(false));
            // A retry of the same Order's reservation remains a no-op even after definition deactivation.
            reservations.reserveForOrder(f.userId, f.stored, f.order, initialQuote[0], NOW.plusSeconds(1));
            jdbc.update("update orders set status='CONFIRMED', payment_status='SUCCEEDED' where id=?", f.orderId);
            em.clear();
            // Campaign validUntil has passed (+5s); the already-reserved snapshot is still honored.
            reservations.consumeForOrder(f.userId, f.orderId, "verified-ref", NOW.plusSeconds(10), NOW.plusSeconds(11));
            reservations.consumeForOrder(f.userId, f.orderId, "verified-ref", NOW.plusSeconds(10), NOW.plusSeconds(11));
        });
        assertCounters(f.voucherId, f.userId, 0, 1, 0, 1);
        assertEquals(1, count("voucher_claims", f.orderId));
    }

    @Test
    void latePaymentEvidenceDoesNotConsumeAnExpiredClaim() {
        Fixture f = reservedFixture("late");
        assertThrows(IllegalStateException.class, () -> inTx(() -> {
            jdbc.update("update orders set status='CONFIRMED', payment_status='SUCCEEDED' where id=?", f.orderId);
            em.clear();
            reservations.consumeForOrder(f.userId, f.orderId, "late-ref", f.order.getExpiresAt(), f.order.getExpiresAt());
        }));
        assertCounters(f.voucherId, f.userId, 1, 0, 1, 0);
        assertEquals(VoucherClaim.State.RESERVED, claimState(f));
    }

    @Test
    void transactionRollbackRestoresClaimAndBothCounters() {
        Fixture f = fixture(voucher(code(), true, NOW.minusSeconds(5), NOW.plusSeconds(600), 5, 5), "rollback");
        assertThrows(IllegalStateException.class, () -> inTx(() -> {
            var quote = reservations.quoteAndLock(f.userId, f.order.getVoucher().orElseThrow().code(), 10_000, NOW).orElseThrow();
            reservations.reserveForOrder(f.userId, f.stored, f.order, quote, NOW);
            throw new IllegalStateException("rollback fixture");
        }));
        assertEquals(0, count("voucher_claims", f.orderId));
        assertCounters(f.voucherId, f.userId, 0, 0, 0, 0);
    }

    @Test
    void checkoutFailureAfterRealVoucherReservationRollsBackOrderClaimAndCounters() {
        String voucherCode = code();
        Fixture f = fixture(voucher(voucherCode, true, NOW.minusSeconds(5), NOW.plusSeconds(600), 5, 5), "checkout-rollback");
        Integer cartItemId = new TransactionTemplate(transactions).execute(status -> {
            CartJpaEntity cart = new CartJpaEntity(f.userId);
            cart.addItem(CartItemType.COURSE, 902, 1);
            em.persist(cart);
            em.flush();
            return cart.getItems().getFirst().getId();
        });
        OrderPersistenceService orderPersistence = new OrderPersistenceService(orders, orderItems,
                (com.drawingcourse.backend.service.OrderCurrentUser) () -> f.userId, transactions);
        // Use the actual Spring Data identity repository rather than any external provider.
        CheckoutIdempotencyService durable = new CheckoutIdempotencyService(checkoutRequests, users, orders, CLOCK, transactions);
        CheckoutIdempotencyPort failAfterDurableCompletion = new CheckoutIdempotencyPort() {
            @Override public CheckoutIdempotencyService.ClaimResult claim(Integer owner, String key, String fingerprint) {
                return durable.claim(owner, key, fingerprint);
            }
            @Override public void complete(Integer owner, Integer requestId, Integer orderId) {
                durable.complete(owner, requestId, orderId);
                throw new IllegalStateException("simulated Checkout failure after voucher reservation");
            }
        };
        CheckoutCoordinator coordinator = new CheckoutCoordinator(failAfterDurableCompletion,
                new JpaCheckoutCartPort(carts),
                id -> Optional.of(new CourseCatalogPort.CourseOffer(id, "Trusted course", 10_000, true)),
                id -> Optional.empty(), new CoursePurchasePort() {
                    @Override public boolean isOwnedBy(Integer owner, Integer course) { return false; }
                    @Override public boolean hasActivePendingPurchase(Integer owner, Integer course, Instant at) { return false; }
                    @Override public void claimForOrder(Integer owner, Integer course, Integer order, Instant at, Instant expires) { }
                    @Override public void confirmForOrder(Integer owner, Integer course, Integer order, Instant at) { }
                    @Override public void releaseForOrder(Integer owner, Integer course, Integer order, String reason, Instant at) { }
                }, new InventoryReservationPort() {
                    @Override public void reserveForOrder(Integer owner, Integer product, int quantity, Integer order, Instant expires) {
                        fail("Course-only Checkout must not reserve Product stock");
                    }
                    @Override public void confirmForOrder(Integer owner, Integer product, Integer order, Instant at) { }
                    @Override public void releaseForOrder(Integer owner, Integer product, Integer order, String reason, Instant at) { }
                }, reservations,
                (owner, items, address) -> 0L,
                new OrderPersistenceCheckoutPort(orderPersistence), CLOCK, transactions);
        var request = new CheckoutRequest(0, List.of(new CheckoutRequest.Selection(cartItemId, 1)),
                Optional.of(voucherCode), Optional.empty());

        assertThrows(IllegalStateException.class, () -> coordinator.checkout(f.userId, "rollback-" + code(), request));
        assertEquals(1, jdbc.queryForObject("select count(*) from orders where user_id=?", Integer.class, f.userId),
                "the fixture Order remains, while the failed Checkout Order is rolled back");
        assertEquals(0, jdbc.queryForObject("select count(*) from checkout_requests where user_id=?", Integer.class, f.userId));
        assertEquals(0, jdbc.queryForObject("select count(*) from voucher_claims where voucher_id=?", Integer.class, f.voucherId));
        assertEquals(0L, jdbc.queryForObject("select reserved_count from vouchers where id=?", Long.class, f.voucherId));
    }

    @Test
    void releaseAfterCampaignDeactivationStillHonorsExistingClaim() {
        Fixture f = reservedFixture("deactivated-release");
        inTx(() -> {
            VoucherJpaEntity definition = vouchers.findByIdForUpdate(f.voucherId).orElseThrow();
            Voucher domain = new com.drawingcourse.backend.service.VoucherPersistenceMapper().toDomain(definition);
            definition.replaceDefinition(domain.withActive(false));
            jdbc.update("update orders set status='CANCELLED', payment_status='CANCELLED' where id=?", f.orderId);
            em.clear();
            reservations.releaseForOrder(f.userId, f.orderId, VoucherClaim.ReleaseReason.CANCELLED, NOW.plusSeconds(1));
        });
        assertCounters(f.voucherId, f.userId, 0, 0, 0, 0);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("transaction coordination timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private Fixture reservedFixture(String label) {
        Fixture f = fixture(voucher(code(), true, NOW.minusSeconds(5), NOW.plusSeconds(600), 5, 5), label);
        inTx(() -> {
            var quote = reservations.quoteAndLock(f.userId, f.order.getVoucher().orElseThrow().code(), 10_000, NOW).orElseThrow();
            reservations.reserveForOrder(f.userId, f.stored, f.order, quote, NOW);
        });
        return f;
    }

    private Fixture fixture(Voucher definition, String label) {
        return new TransactionTemplate(transactions).execute(status -> {
            String email = "voucher-" + label + "-" + UUID.randomUUID() + "@example.test";
            User user = users.saveAndFlush(User.builder().fullName("Voucher test").email(email)
                    .passwordHash("test-only").role(Role.USER).isActive(true).build());
            VoucherJpaEntity voucher = VoucherJpaEntity.fromDefinition(definition);
            em.persist(voucher);
            em.flush();
            return createOrderFixture(user.getId(), voucher.getId(), definition);
        });
    }

    private Fixture fixtureForExistingVoucher(Integer voucherId, Voucher definition, String label) {
        return new TransactionTemplate(transactions).execute(status -> {
            String email = "voucher-" + label + "-" + UUID.randomUUID() + "@example.test";
            User user = users.saveAndFlush(User.builder().fullName("Voucher test").email(email)
                    .passwordHash("test-only").role(Role.USER).isActive(true).build());
            return createOrderFixture(user.getId(), voucherId, definition);
        });
    }

    private Fixture createOrderFixture(Integer userId, Integer voucherId, Voucher definition) {
        Order order = Order.create(userId, List.of(new OrderLineSnapshot(
                OrderItemType.COURSE, 701, "Course snapshot", 1, 10_000, true)), 0,
                Optional.of(definition.toOrderSnapshot()), CLOCK);
        OrderJpaEntity orderRow = OrderJpaEntity.fromSnapshot(order);
        em.persist(orderRow);
        em.flush();
        StoredOrder stored = new OrderPersistenceMapper().toStored(orderRow);
        return new Fixture(userId, voucherId, orderRow.getId(), order, stored);
    }

    private Voucher voucher(String code, boolean active, Instant from, Instant until, long totalLimit, long userLimit) {
        return new Voucher(code, OrderVoucher.Kind.FIXED_VND, 1_000, OptionalLong.empty(), active,
                from, until, 0, OptionalLong.of(totalLimit), OptionalLong.of(userLimit));
    }

    private void assertCounters(Integer voucherId, Integer userId, long totalReserved, long totalRedeemed,
                                long perUserReserved, long perUserRedeemed) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var voucher = vouchers.findByIdForUpdate(voucherId).orElseThrow();
            var usage = usages.findByVoucherIdAndUserId(voucherId, userId).orElse(null);
            assertEquals(totalReserved, voucher.getReservedCount());
            assertEquals(totalRedeemed, voucher.getRedeemedCount());
            assertEquals(perUserReserved, usage == null ? 0 : usage.getReservedCount());
            assertEquals(perUserRedeemed, usage == null ? 0 : usage.getRedeemedCount());
        });
    }

    private VoucherClaim.State claimState(Fixture f) {
        return new TransactionTemplate(transactions).execute(status ->
                new com.drawingcourse.backend.service.VoucherPersistenceMapper().toDomain(
                        claims.findByOrderIdAndUserId(f.orderId, f.userId).orElseThrow()).state());
    }
    private int count(String table, Integer orderId) {
        return jdbc.queryForObject("select count(*) from " + table + " where order_id=?", Integer.class, orderId);
    }
    private void inTx(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> action.run());
    }
    private static String code() { return "V-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }
    private record Fixture(Integer userId, Integer voucherId, Integer orderId, Order order, StoredOrder stored) { }
}
