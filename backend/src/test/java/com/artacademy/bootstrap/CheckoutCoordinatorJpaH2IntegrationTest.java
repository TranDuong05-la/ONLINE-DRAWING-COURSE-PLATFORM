package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.*;
import com.drawingcourse.backend.service.checkout.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** Real H2 transaction test with explicit test-only C/E/Voucher participants. Not production adapters. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class CheckoutCoordinatorJpaH2IntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private CartRepository carts;
    @Autowired private CheckoutRequestRepository checkoutRequests;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void createTestOnlyParticipantTable() {
        jdbc.execute("CREATE TABLE checkout_test_effects (effect_key VARCHAR(32) PRIMARY KEY)");
    }

    @AfterEach
    void removeTestOnlyParticipantTable() {
        jdbc.execute("DROP TABLE checkout_test_effects");
    }

    @Test
    void mixedOrderClaimsAndTestParticipantsCommitTogetherAndCompletedRetryPrecedesChangedRevision() {
        Fixture fixture = fixture(false);
        var result = fixture.coordinator.checkout(fixture.userId, fixture.key, fixture.request);
        assertFalse(result.replayed());
        assertEquals(1, countOrders(fixture.userId));
        assertEquals(1, countRequests(fixture.userId));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM checkout_test_effects", Integer.class));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT state FROM checkout_requests WHERE user_id=? AND idempotency_key=?", String.class,
                fixture.userId, fixture.key));
        assertEquals(205_000L, jdbc.queryForObject("SELECT total_vnd FROM orders WHERE id=?", Long.class, result.orderId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id=?", Integer.class, result.orderId()));

        new TransactionTemplate(transactions).executeWithoutResult(status ->
                jdbc.update("UPDATE carts SET revision=7 WHERE user_id=?", fixture.userId));
        assertEquals(new CheckoutCoordinator.CheckoutResult(result.orderId(), true),
                fixture.coordinator.checkout(fixture.userId, fixture.key, fixture.request));
        assertEquals(1, countOrders(fixture.userId));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM checkout_test_effects", Integer.class));
    }

    @Test
    void reservationFailureRollsBackOrderIdempotencyAndEarlierParticipantWrites() {
        Fixture fixture = fixture(true);
        assertThrows(IllegalStateException.class,
                () -> fixture.coordinator.checkout(fixture.userId, fixture.key, fixture.request));

        assertEquals(0, countOrders(fixture.userId));
        assertEquals(0, countRequests(fixture.userId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM checkout_test_effects", Integer.class));
        assertEquals(0L, jdbc.queryForObject("SELECT revision FROM carts WHERE user_id=?", Long.class, fixture.userId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id=(SELECT id FROM carts WHERE user_id=?)",
                Integer.class, fixture.userId));
    }

    private Fixture fixture(boolean failVoucherReservation) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Integer userId = new TransactionTemplate(transactions).execute(status -> {
            User user = users.saveAndFlush(User.builder().fullName("Checkout integration test")
                    .email("checkout-" + suffix + "@example.test").passwordHash("test-hash")
                    .role(Role.USER).isActive(true).build());
            CartJpaEntity cart = new CartJpaEntity(user.getId());
            cart.addItem(CartItemType.COURSE, 701, 1);
            cart.addItem(CartItemType.PRODUCT, 801, 2);
            carts.saveAndFlush(cart);
            return user.getId();
        });
        var selections = new TransactionTemplate(transactions).execute(status ->
                carts.findByUserId(userId).orElseThrow().getItems().stream().map(item ->
                        new CheckoutRequest.Selection(item.getId(), item.getQuantity())).toList());
        var request = new CheckoutRequest(0L, selections, Optional.of("SAVE10"), Optional.empty());
        var identity = (OrderCurrentUser) () -> userId;
        var orderPersistence = new OrderPersistenceService(orders, orderItems, identity, transactions);
        var idempotency = new CheckoutIdempotencyService(checkoutRequests, users, orders,
                Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC), transactions);
        var coursePort = new TestCoursePort(jdbc);
        var inventoryPort = new TestInventoryPort(jdbc);
        var voucherPort = new TestVoucherPort(jdbc, failVoucherReservation);
        Clock clock = Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC);
        var coordinator = new CheckoutCoordinator(idempotency, new JpaCheckoutCartPort(carts),
                id -> Optional.of(new CourseCatalogPort.CourseOffer(id, "Course historical name", 100_000, true)),
                id -> Optional.of(new ProductCatalogPort.ProductOffer(id, "Product historical name", 50_000, true)),
                coursePort, inventoryPort, voucherPort, (owner, productList, address) -> 15_000L,
                new OrderPersistenceCheckoutPort(orderPersistence), clock, transactions);
        return new Fixture(userId, "checkout-" + suffix, request, coordinator);
    }

    private int countOrders(Integer userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, userId);
    }
    private int countRequests(Integer userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?", Integer.class, userId);
    }

    private record Fixture(Integer userId, String key, CheckoutRequest request, CheckoutCoordinator coordinator) { }

    private static final class TestCoursePort implements CoursePurchasePort {
        private final JdbcTemplate jdbc;
        private TestCoursePort(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public boolean isOwnedBy(Integer user, Integer course) { return false; }
        @Override public boolean hasActivePendingPurchase(Integer user, Integer course, Instant at) { return false; }
        @Override public void claimForOrder(Integer user, Integer course, Integer order, Instant at, Instant expiresAt) {
            jdbc.update("INSERT INTO checkout_test_effects(effect_key) VALUES ('course')");
        }
        @Override public void confirmForOrder(Integer user, Integer course, Integer order, Instant at) { }
        @Override public void releaseForOrder(Integer user, Integer course, Integer order, String reason, Instant at) { }
    }

    private static final class TestInventoryPort implements InventoryReservationPort {
        private final JdbcTemplate jdbc;
        private TestInventoryPort(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public void reserveForOrder(Integer user, Integer product, int quantity, Integer order, Instant expiresAt) {
            jdbc.update("INSERT INTO checkout_test_effects(effect_key) VALUES ('inventory')");
        }
        @Override public void confirmForOrder(Integer user, Integer product, Integer order, Instant at) { }
        @Override public void releaseForOrder(Integer user, Integer product, Integer order, String reason, Instant at) { }
    }

    private static final class TestVoucherPort implements VoucherReservationPort {
        private final JdbcTemplate jdbc;
        private final boolean fail;
        private TestVoucherPort(JdbcTemplate jdbc, boolean fail) { this.jdbc = jdbc; this.fail = fail; }
        @Override public Optional<Quote> quoteAndLock(Integer user, String code, long eligible, Instant at) {
            return Optional.of(new Quote(1, 1, new OrderVoucher(code, OrderVoucher.Kind.FIXED_VND, 10_000, 10_000)));
        }
        @Override public void reserveForOrder(Integer user, StoredOrder stored, Order order, Quote quote, Instant at) {
            jdbc.update("INSERT INTO checkout_test_effects(effect_key) VALUES ('voucher')");
            if (fail) throw new IllegalStateException("test reservation failure");
        }
        @Override public void releaseForOrder(Integer user, Integer order, VoucherClaim.ReleaseReason reason, Instant at) { }
        @Override public void consumeForOrder(Integer user, Integer order, String reference, Instant paidAt, Instant at) { }
    }
}
