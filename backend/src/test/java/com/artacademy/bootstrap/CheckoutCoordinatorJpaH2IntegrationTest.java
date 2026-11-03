package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.course.Course;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.claim.CourseClaim;
import com.artacademy.learning.claim.CourseClaimConflictException;
import com.artacademy.learning.claim.CourseClaimRepository;
import com.artacademy.learning.claim.CourseClaimService;
import com.artacademy.learning.claim.CoursePurchasePortAdapter;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.*;
import com.drawingcourse.backend.service.checkout.*;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts;
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
    @Autowired private CourseRepository courseCatalog;
    @Autowired private CourseClaimRepository courseClaims;
    @Autowired private CourseClaimService courseClaimService;
    @Autowired private CartRepository carts;
    @Autowired private CheckoutRequestRepository checkoutRequests;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private OrderShippingAddressRepository shippingAddresses;
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
        assertEquals("1 Demo Street", jdbc.queryForObject(
                "SELECT address_line FROM order_shipping_addresses WHERE order_id=?", String.class, result.orderId()));

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
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_shipping_addresses a JOIN orders o ON o.id=a.order_id WHERE o.user_id=?",
                Integer.class, fixture.userId));
        assertEquals(0, countRequests(fixture.userId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM checkout_test_effects", Integer.class));
        assertEquals(0L, jdbc.queryForObject("SELECT revision FROM carts WHERE user_id=?", Long.class, fixture.userId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id=(SELECT id FROM carts WHERE user_id=?)",
                Integer.class, fixture.userId));
    }

    @Test
    void courseOnlyCheckoutUsesExplicitTestCourseParticipantAndReplayCreatesNoPaymentOrSettlement() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Integer userId = new TransactionTemplate(transactions).execute(status -> {
            User user = users.saveAndFlush(User.builder().fullName("Course-only Checkout test")
                    .email("course-only-" + suffix + "@example.test").passwordHash("test-hash")
                    .role(Role.USER).isActive(true).build());
            CartJpaEntity cart = new CartJpaEntity(user.getId());
            cart.addItem(CartItemType.COURSE, 971, 1);
            carts.saveAndFlush(cart);
            return user.getId();
        });
        var selection = new TransactionTemplate(transactions).execute(status -> {
            var row = carts.findByUserId(userId).orElseThrow().getItems().getFirst();
            return new CheckoutRequest.Selection(row.getId(), 1);
        });
        var request = new CheckoutRequest(0, List.of(selection), Optional.empty(), Optional.empty());
        var clock = Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC);
        var orderPersistence = new OrderPersistenceService(orders, orderItems, () -> userId, transactions);
        var idempotency = new CheckoutIdempotencyService(checkoutRequests, users, orders, clock, transactions);
        var coordinator = CheckoutCoordinator.courseOnly(idempotency, new JpaCheckoutCartPort(carts),
                id -> Optional.of(new CourseCatalogPort.CourseOffer(id, "Course-only snapshot", 25_000, true)),
                new TestCoursePort(jdbc), new TestVoucherPort(jdbc, false),
                new OrderPersistenceCheckoutPort(orderPersistence), clock, transactions);
        String key = "course-only-" + suffix;

        var first = coordinator.checkoutCoursesOnly(userId, key, request);
        var replay = coordinator.checkoutCoursesOnly(userId, key, request);

        assertFalse(first.replayed());
        assertEquals(new CheckoutCoordinator.CheckoutResult(first.orderId(), true), replay);
        assertEquals(1, countOrders(userId));
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class,
                first.orderId()));
        assertEquals("CREATED", jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class,
                first.orderId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", Integer.class,
                first.orderId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Integer.class,
                first.orderId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM checkout_test_effects WHERE effect_key='course'",
                Integer.class));
    }

    @Test
    void checkoutPersistsRealOrderItemIdentityOnClaimsAndConfirmationUsesTheSameClaims() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User user = saveUser("claim-checkout-" + suffix);
        Course first = saveCourse("claim-first-" + suffix);
        Course second = saveCourse("claim-second-" + suffix);
        Integer firstId = Math.toIntExact(first.getId());
        Integer secondId = Math.toIntExact(second.getId());
        CartJpaEntity cart = new CartJpaEntity(user.getId());
        cart.addItem(CartItemType.COURSE, secondId, 1);
        cart.addItem(CartItemType.COURSE, firstId, 1);
        carts.saveAndFlush(cart);
        List<CheckoutRequest.Selection> selections = new TransactionTemplate(transactions).execute(status ->
                carts.findByUserId(user.getId()).orElseThrow().getItems().stream()
                        .map(item -> new CheckoutRequest.Selection(item.getId(), item.getQuantity())).toList());

        var clock = Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC);
        var orderPersistence = new OrderPersistenceService(orders, orderItems, () -> user.getId(), transactions);
        var idempotency = new CheckoutIdempotencyService(checkoutRequests, users, orders, clock, transactions);
        var courseAdapter = new CoursePurchasePortAdapter(courseClaimService);
        var coordinator = CheckoutCoordinator.courseOnly(idempotency, new JpaCheckoutCartPort(carts),
                id -> Optional.of(new CourseCatalogPort.CourseOffer(id, "Trusted persisted Course", 25_000, true)),
                courseAdapter, new TestVoucherPort(jdbc, false), new OrderPersistenceCheckoutPort(orderPersistence),
                clock, transactions);
        String key = "real-claims-" + suffix;
        var request = new CheckoutRequest(0, selections, Optional.empty(), Optional.empty());

        var result = coordinator.checkout(user.getId(), key, request);

        List<Long> courseOrder = jdbc.query("SELECT course_id FROM course_claims WHERE order_id=? ORDER BY id",
                (rs, row) -> rs.getLong(1), result.orderId());
        assertEquals(List.of(first.getId(), second.getId()), courseOrder,
                "Checkout and C must reserve claims in ascending Course ID order");
        for (Integer courseId : List.of(firstId, secondId)) {
            Integer persistedOrderItemId = jdbc.queryForObject(
                    "SELECT id FROM order_items WHERE order_id=? AND item_type='COURSE' AND item_id=?",
                    Integer.class, result.orderId(), courseId);
            List<CourseClaim> linkedClaims = courseClaims.findByOrderItemIdOrderByCourseIdAsc(persistedOrderItemId);
            assertEquals(1, linkedClaims.size());
            assertEquals(result.orderId(), linkedClaims.getFirst().getOrderId());
            assertEquals(courseId.longValue(), linkedClaims.getFirst().getCourseId());
            assertEquals(persistedOrderItemId, linkedClaims.getFirst().getOrderItemId());
        }

        assertEquals(new CheckoutCoordinator.CheckoutResult(result.orderId(), true),
                coordinator.checkout(user.getId(), key, request));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM course_claims WHERE order_id=?", Integer.class,
                result.orderId()));

        Instant confirmedAt = Instant.parse("2026-10-28T05:01:00Z");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            courseClaimService.confirmForOrder(user.getId(), firstId, result.orderId(), confirmedAt);
            courseClaimService.confirmForOrder(user.getId(), secondId, result.orderId(), confirmedAt);
        });
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_claims WHERE order_id=? AND status='CONFIRMED'", Integer.class,
                result.orderId()));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=? AND source_order_item_id IS NOT NULL",
                Integer.class, result.orderId()));
    }

    @Test
    void laterCourseClaimConflictRollsBackPersistedOrderItemsAndEarlierClaims() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User user = saveUser("claim-rollback-" + suffix);
        Course first = saveCourse("claim-rollback-first-" + suffix);
        Course second = saveCourse("claim-rollback-second-" + suffix);
        Integer firstId = Math.toIntExact(first.getId());
        Integer secondId = Math.toIntExact(second.getId());
        Instant now = Instant.parse("2026-10-28T05:00:00Z");
        List<CourseClaimContracts.ClaimLine> competingLine = List.of(new CourseClaimContracts.ClaimLine(
                99001, second.getId(), CourseClaimContracts.ClaimSourceType.STANDALONE_COURSE, null));
        new TransactionTemplate(transactions).executeWithoutResult(status -> courseClaimService.reserve(
                new CourseClaimContracts.ReservationCommand(user.getId(), 99001, competingLine, now,
                        now.plusSeconds(1800))));

        CartJpaEntity cart = new CartJpaEntity(user.getId());
        cart.addItem(CartItemType.COURSE, firstId, 1);
        cart.addItem(CartItemType.COURSE, secondId, 1);
        carts.saveAndFlush(cart);
        List<CheckoutRequest.Selection> selections = new TransactionTemplate(transactions).execute(status ->
                carts.findByUserId(user.getId()).orElseThrow().getItems().stream()
                        .map(item -> new CheckoutRequest.Selection(item.getId(), item.getQuantity())).toList());
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var orderPersistence = new OrderPersistenceService(orders, orderItems, () -> user.getId(), transactions);
        var idempotency = new CheckoutIdempotencyService(checkoutRequests, users, orders, clock, transactions);
        var actualClaims = new CoursePurchasePortAdapter(courseClaimService);
        CoursePurchasePort simulateConcurrentClaimAfterPrecheck = new CoursePurchasePort() {
            @Override public boolean isOwnedBy(Integer owner, Integer course) { return false; }
            @Override public boolean hasActivePendingPurchase(Integer owner, Integer course, Instant at) { return false; }
            @Override public void claimForOrder(Integer owner, Integer course, Integer order, Integer orderItem,
                                                Instant at, Instant expires) {
                actualClaims.claimForOrder(owner, course, order, orderItem, at, expires);
            }
            @Override public void confirmForOrder(Integer owner, Integer course, Integer order, Instant at) {
                actualClaims.confirmForOrder(owner, course, order, at);
            }
            @Override public void releaseForOrder(Integer owner, Integer course, Integer order, String reason,
                                                  Instant at) {
                actualClaims.releaseForOrder(owner, course, order, reason, at);
            }
        };
        var coordinator = CheckoutCoordinator.courseOnly(idempotency, new JpaCheckoutCartPort(carts),
                id -> Optional.of(new CourseCatalogPort.CourseOffer(id, "Trusted persisted Course", 25_000, true)),
                simulateConcurrentClaimAfterPrecheck, new TestVoucherPort(jdbc, false),
                new OrderPersistenceCheckoutPort(orderPersistence), clock, transactions);
        String key = "claim-rollback-" + suffix;
        var request = new CheckoutRequest(0, selections, Optional.empty(), Optional.empty());

        assertThrows(CourseClaimConflictException.class, () -> coordinator.checkout(user.getId(), key, request));

        assertEquals(0, countOrders(user.getId()));
        assertEquals(0, countRequests(user.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_items oi JOIN orders o ON o.id=oi.order_id "
                + "WHERE o.user_id=?", Integer.class, user.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM course_claims WHERE user_id=?", Integer.class,
                user.getId()));
        assertEquals(99001, jdbc.queryForObject("SELECT order_id FROM course_claims WHERE user_id=?", Integer.class,
                user.getId()));
    }

    @Test
    void newCheckoutClaimReplayRejectsMismatchedOrderItemIdentity() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User user = saveUser("claim-identity-" + suffix);
        Course course = saveCourse("claim-identity-course-" + suffix);
        Instant now = Instant.parse("2026-10-28T05:00:00Z");
        var first = new CourseClaimContracts.ReservationCommand(user.getId(), 88001,
                List.of(new CourseClaimContracts.ClaimLine(88002, course.getId(),
                        CourseClaimContracts.ClaimSourceType.STANDALONE_COURSE, null)), now, now.plusSeconds(1800));
        var mismatch = new CourseClaimContracts.ReservationCommand(user.getId(), 88001,
                List.of(new CourseClaimContracts.ClaimLine(88003, course.getId(),
                        CourseClaimContracts.ClaimSourceType.STANDALONE_COURSE, null)), now, now.plusSeconds(1800));
        new TransactionTemplate(transactions).executeWithoutResult(status -> courseClaimService.reserve(first));

        assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(transactions).executeWithoutResult(status -> courseClaimService.reserve(mismatch)));
        assertEquals(88002, jdbc.queryForObject("SELECT order_item_id FROM course_claims WHERE order_id=? AND course_id=?",
                Integer.class, 88001, course.getId()));
    }

    @Test
    void checkoutClaimRetainsOrderItemIdentityWhenReleasedForExpiration() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User user = saveUser("claim-expire-" + suffix);
        Course course = saveCourse("claim-expire-course-" + suffix);
        Integer courseId = Math.toIntExact(course.getId());
        CartJpaEntity cart = new CartJpaEntity(user.getId());
        cart.addItem(CartItemType.COURSE, courseId, 1);
        carts.saveAndFlush(cart);
        var selection = new TransactionTemplate(transactions).execute(status ->
                carts.findByUserId(user.getId()).orElseThrow().getItems().stream()
                        .map(item -> new CheckoutRequest.Selection(item.getId(), item.getQuantity())).toList());
        var clock = Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC);
        var orderPersistence = new OrderPersistenceService(orders, orderItems, () -> user.getId(), transactions);
        var idempotency = new CheckoutIdempotencyService(checkoutRequests, users, orders, clock, transactions);
        var coordinator = CheckoutCoordinator.courseOnly(idempotency, new JpaCheckoutCartPort(carts),
                id -> Optional.of(new CourseCatalogPort.CourseOffer(id, "Trusted persisted Course", 25_000, true)),
                new CoursePurchasePortAdapter(courseClaimService), new TestVoucherPort(jdbc, false),
                new OrderPersistenceCheckoutPort(orderPersistence), clock, transactions);

        var result = coordinator.checkout(user.getId(), "expire-" + suffix,
                new CheckoutRequest(0, selection, Optional.empty(), Optional.empty()));
        Integer itemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=? AND item_id=?",
                Integer.class, result.orderId(), courseId);
        Instant deadline = Instant.parse("2026-10-28T05:30:00Z");
        new TransactionTemplate(transactions).executeWithoutResult(status -> courseClaimService.releaseForOrder(
                user.getId(), courseId, result.orderId(), "EXPIRED", deadline));

        CourseClaim released = courseClaims.findByOrderItemIdOrderByCourseIdAsc(itemId).getFirst();
        assertEquals(CourseClaim.Status.RELEASED, released.getStatus());
        assertEquals(itemId, released.getOrderItemId());
        assertEquals("EXPIRED", released.getReleaseReason());
    }

    private User saveUser(String emailPrefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return new TransactionTemplate(transactions).execute(status -> users.saveAndFlush(User.builder()
                .fullName("Course claim checkout test").email(emailPrefix + "-" + suffix + "@example.test")
                .passwordHash("test-hash").role(Role.USER).isActive(true).build()));
    }

    private Course saveCourse(String suffix) {
        Course course = new Course();
        course.setCode("C-" + UUID.randomUUID().toString().substring(0, 8));
        course.setSlug("claim-" + suffix);
        course.setTitle("Claim checkout Course");
        course.setPrice(25_000);
        course.setLevel(CourseLevel.BASIC);
        course.setStatus(CourseStatus.PUBLISHED);
        return courseCatalog.saveAndFlush(course);
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
        var request = new CheckoutRequest(0L, selections, Optional.of("SAVE10"),
                Optional.of(new CheckoutRequest.Address("Demo Buyer", "0900000000", "1 Demo Street",
                        "District 1, Ho Chi Minh City", "700000", "VN")));
        var identity = (OrderCurrentUser) () -> userId;
        var orderPersistence = new OrderPersistenceService(orders, orderItems, identity, transactions, shippingAddresses);
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
        @Override public void claimForOrder(Integer user, Integer course, Integer order, Integer orderItem, Instant at, Instant expiresAt) {
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
        @Override public void consumeForOrder(Integer user, Integer order, String reference, Instant paidAt,
                                              Instant receivedAt, Instant settledAt) { }
    }
}
