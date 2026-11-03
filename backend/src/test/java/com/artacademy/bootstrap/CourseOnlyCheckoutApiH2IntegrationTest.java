package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.course.Course;
import com.artacademy.course.CourseIncludedKit;
import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.EnrollmentService;
import com.artacademy.learning.claim.CourseClaimConflictException;
import com.artacademy.learning.claim.CourseClaimService;
import com.artacademy.learning.claim.CoursePurchasePortAdapter;
import com.artacademy.product.Product;
import com.artacademy.product.ProductRepository;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.controller.CourseOnlyCheckoutController;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.entity.CartJpaEntity;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.entity.Voucher;
import com.drawingcourse.backend.entity.VoucherJpaEntity;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts;
import com.drawingcourse.backend.repository.CartRepository;
import com.drawingcourse.backend.repository.OrderItemRepository;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.VoucherRepository;
import com.drawingcourse.backend.service.OrderPersistenceService;
import com.drawingcourse.backend.service.checkout.CheckoutCoordinator;
import com.drawingcourse.backend.service.checkout.CoursePurchasePort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.cart.course-only.api-enabled=true",
        "app.checkout.course-only.enabled=true",
        "app.checkout.course-only.http-enabled=true",
        "app.checkout.course-only.catalog-adapter.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:course_only_checkout_api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CourseOnlyCheckoutApiH2IntegrationTest.TestClaimFailureConfiguration.class)
class CourseOnlyCheckoutApiH2IntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private CourseKitRepository kits;
    @Autowired private ProductRepository products;
    @Autowired private CartRepository carts;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private VoucherRepository vouchers;
    @Autowired private CourseClaimService claims;
    @Autowired private EnrollmentService enrollments;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private JwtService jwt;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClaimFailureControl claimFailure;

    @Test
    void authenticatedCourseCartCheckoutPersistsTrustedOrderClaimAndReplayWithoutPaymentOrEnrollment() throws Exception {
        assertThat(context.getBeansOfType(CourseOnlyCheckoutController.class)).hasSize(1);
        assertThat(context.getBeansOfType(CheckoutCoordinator.class)).hasSize(1);
        User buyer = user();
        Course course = course("trusted", CourseStatus.PUBLISHED, 60_000, 45_000L);
        String auth = bearer(buyer);

        mvc.perform(get("/api/checkout/course-only").header("Authorization", auth))
                .andExpect(status().isMethodNotAllowed());
        CartLine cartLine = addCourse(auth, Math.toIntExact(course.getId()));
        String request = checkoutBody(cartLine.revision(), List.of(cartLine.cartItemId()), null);
        String key = "course-order-" + UUID.randomUUID();

        var created = mvc.perform(post("/api/checkout/course-only").header("Authorization", auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn();
        JsonNode firstBody = json.readTree(created.getResponse().getContentAsString());
        int orderId = firstBody.path("data").path("orderId").asInt();
        assertThat(orderId).isPositive();
        assertThat(firstBody.path("data").path("replayed").asBoolean()).isFalse();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=? AND user_id=?", String.class,
                orderId, buyer.getId())).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, orderId))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id=?", Integer.class, orderId))
                .isEqualTo(1);
        Integer orderItemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Integer.class, orderId);
        assertThat(jdbc.queryForObject("SELECT item_type FROM order_items WHERE id=?", String.class, orderItemId))
                .isEqualTo("COURSE");
        assertThat(jdbc.queryForObject("SELECT name_snapshot FROM order_items WHERE id=?", String.class, orderItemId))
                .isEqualTo(course.getTitle());
        assertThat(jdbc.queryForObject("SELECT unit_price_vnd FROM order_items WHERE id=?", Long.class, orderItemId))
                .isEqualTo(45_000L);
        assertThat(jdbc.queryForObject("SELECT order_item_id FROM course_claims WHERE order_id=? AND course_id=?",
                Integer.class, orderId, course.getId())).isEqualTo(orderItemId);
        assertThat(jdbc.queryForObject("SELECT status FROM course_claims WHERE order_id=? AND course_id=?",
                String.class, orderId, course.getId())).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", Integer.class, orderId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Integer.class, orderId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?",
                Integer.class, orderId)).isZero();

        mvc.perform(post("/api/checkout/course-only").header("Authorization", auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.getId()))
                .isEqualTo(1);
        String changedIntent = checkoutBody(cartLine.revision(), List.of(cartLine.cartItemId()), "DIFFERENT");
        mvc.perform(post("/api/checkout/course-only").header("Authorization", auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(changedIntent))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.getId()))
                .isEqualTo(1);
    }

    @Test
    void unauthenticatedAndStaleRevisionOrForgedCartItemRequestsAreRejected() throws Exception {
        mvc.perform(post("/api/checkout/course-only").contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(0, List.of(1), null)))
                .andExpect(status().isUnauthorized());
        User buyer = user();
        Course course = course("revision", CourseStatus.PUBLISHED, 20_000, null);
        String auth = bearer(buyer);
        CartLine line = addCourse(auth, Math.toIntExact(course.getId()));
        String key = "stale-" + UUID.randomUUID();
        mvc.perform(post("/api/checkout/course-only").header("Authorization", auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(line.revision() + 1, List.of(line.cartItemId()), null)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/checkout/course-only").header("Authorization", auth)
                        .header("Idempotency-Key", "forged-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(line.revision(), List.of(line.cartItemId() + 100), null)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.getId()))
                .isZero();
    }

    @Test
    void checkoutRejectsProductMixedOwnedPendingUnpublishedAndKitCourses() throws Exception {
        User buyer = user();
        String auth = bearer(buyer);
        Course mixedCourse = course("mixed", CourseStatus.PUBLISHED, 25_000, null);
        CartJpaEntity mixed = new CartJpaEntity(buyer.getId());
        mixed.addItem(CartItemType.COURSE, Math.toIntExact(mixedCourse.getId()), 1);
        mixed.addItem(CartItemType.PRODUCT, 91, 1);
        carts.saveAndFlush(mixed);
        List<Integer> mixedIds = cartItemIds(buyer.getId());
        mvc.perform(post("/api/checkout/course-only").header("Authorization", auth)
                        .header("Idempotency-Key", "mixed-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(0, mixedIds, null)))
                .andExpect(status().isConflict());

        Course owned = course("owned", CourseStatus.PUBLISHED, 25_000, null);
        assertCheckoutRejectedForDirectCourseCart(owned, "owned", owner ->
                enrollments.grantManually(owner.getId(), List.of(owned.getId())));

        Course pending = course("pending", CourseStatus.PUBLISHED, 25_000, null);
        assertCheckoutRejectedForDirectCourseCart(pending, "pending", owner -> seedPendingClaim(owner, pending));

        Course draft = course("draft", CourseStatus.DRAFT, 25_000, null);
        assertCheckoutRejectedForDirectCourseCart(draft, "draft", ignored -> { });

        Course kitCourse = course("kit", CourseStatus.PUBLISHED, 25_000, null);
        Product physical = products.saveAndFlush(new Product("KIT-" + UUID.randomUUID(), "Included", 500, null));
        kits.saveAndFlush(new CourseIncludedKit(kitCourse, physical, 1));
        assertCheckoutRejectedForDirectCourseCart(kitCourse, "kit", ignored -> { });
    }

    @Test
    void voucherReservationAndDiscountSnapshotCommitWithTheOrder() throws Exception {
        User buyer = user();
        Course course = course("voucher", CourseStatus.PUBLISHED, 30_000, null);
        String code = "SAVE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Voucher definition = new Voucher(code, OrderVoucher.Kind.FIXED_VND, 5_000, OptionalLong.empty(), true,
                now.minusSeconds(3600), now.plusSeconds(86_400), 10_000,
                OptionalLong.of(10), OptionalLong.of(2));
        VoucherJpaEntity voucher = vouchers.saveAndFlush(VoucherJpaEntity.fromDefinition(definition));
        CartLine line = addCourse(bearer(buyer), Math.toIntExact(course.getId()));

        var response = mvc.perform(post("/api/checkout/course-only").header("Authorization", bearer(buyer))
                        .header("Idempotency-Key", "voucher-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(line.revision(), List.of(line.cartItemId()), code)))
                .andExpect(status().isCreated()).andReturn();
        int orderId = json.readTree(response.getResponse().getContentAsString()).path("data").path("orderId").asInt();
        assertThat(jdbc.queryForObject("SELECT voucher_code FROM orders WHERE id=?", String.class, orderId)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT discount_vnd FROM orders WHERE id=?", Long.class, orderId)).isEqualTo(5_000L);
        assertThat(jdbc.queryForObject("SELECT net_amount_vnd FROM order_items WHERE order_id=?", Long.class, orderId))
                .isEqualTo(25_000L);
        assertThat(jdbc.queryForObject("SELECT allocated_discount_vnd FROM order_items WHERE order_id=?", Long.class, orderId))
                .isEqualTo(5_000L);
        assertThat(jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class, orderId))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT reserved_count FROM vouchers WHERE id=?", Long.class, voucher.getId()))
                .isEqualTo(1L);
    }

    @Test
    void laterCourseClaimFailureRollsBackCheckoutHttpTransactionCompletely() throws Exception {
        User buyer = user();
        Course first = course("first", CourseStatus.PUBLISHED, 25_000, null);
        Course second = course("second", CourseStatus.PUBLISHED, 25_000, null);
        CartJpaEntity cart = new CartJpaEntity(buyer.getId());
        cart.addItem(CartItemType.COURSE, Math.toIntExact(first.getId()), 1);
        cart.addItem(CartItemType.COURSE, Math.toIntExact(second.getId()), 1);
        carts.saveAndFlush(cart);
        List<Integer> selected = cartItemIds(buyer.getId());
        claimFailure.failCourseId = Math.toIntExact(second.getId());
        try {
            mvc.perform(post("/api/checkout/course-only").header("Authorization", bearer(buyer))
                            .header("Idempotency-Key", "rollback-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(checkoutBody(0, selected, null)))
                    .andExpect(status().isConflict());
        } finally {
            claimFailure.failCourseId = null;
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?", Integer.class, buyer.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_claims WHERE user_id=?", Integer.class, buyer.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items oi JOIN orders o ON o.id=oi.order_id WHERE o.user_id=?",
                Integer.class, buyer.getId())).isZero();
    }

    private void assertCheckoutRejectedForDirectCourseCart(Course course, String idempotencySuffix,
                                                           Consumer<User> prepareOwner) throws Exception {
        User buyer = user();
        prepareOwner.accept(buyer);
        String auth = bearer(buyer);
        CartJpaEntity cart = new CartJpaEntity(buyer.getId());
        cart.addItem(CartItemType.COURSE, Math.toIntExact(course.getId()), 1);
        carts.saveAndFlush(cart);
        Integer itemId = jdbc.queryForObject("SELECT id FROM cart_items WHERE cart_id=(SELECT id FROM carts WHERE user_id=?) "
                + "AND item_type='COURSE' AND item_id=? ORDER BY id DESC FETCH FIRST 1 ROWS ONLY",
                Integer.class, buyer.getId(), Math.toIntExact(course.getId()));
        mvc.perform(post("/api/checkout/course-only").header("Authorization", auth)
                        .header("Idempotency-Key", idempotencySuffix + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutBody(0, List.of(itemId), null)))
                .andExpect(status().isConflict());
    }

    private void seedPendingClaim(User buyer, Course course) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var orderPersistence = new OrderPersistenceService(orders, orderItems, () -> buyer.getId(), transactions);
        Order snapshot = Order.create(buyer.getId(), List.of(new OrderLineSnapshot(OrderItemType.COURSE,
                Math.toIntExact(course.getId()), course.getTitle(), 1, course.effectivePrice(), true)), 0,
                Optional.empty(), Clock.fixed(now, ZoneOffset.UTC));
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            var stored = orderPersistence.insertNew(snapshot);
            var item = stored.itemIdentities().getFirst();
            claims.reserve(new CourseClaimContracts.ReservationCommand(buyer.getId(), stored.id(), List.of(
                    new CourseClaimContracts.ClaimLine(item.id(), course.getId(),
                            CourseClaimContracts.ClaimSourceType.STANDALONE_COURSE, null)), now, snapshot.getExpiresAt()));
        });
    }

    private CartLine addCourse(String auth, Integer courseId) throws Exception {
        var response = mvc.perform(post("/api/cart/items").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"COURSE\",\"itemId\":" + courseId + ",\"quantity\":1}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = json.readTree(response.getResponse().getContentAsString()).path("data").path("items").get(0);
        return new CartLine(item.path("cartItemId").asInt(),
                json.readTree(response.getResponse().getContentAsString()).path("data").path("revision").asLong());
    }

    private List<Integer> cartItemIds(Integer userId) {
        return jdbc.query("SELECT ci.id FROM cart_items ci JOIN carts c ON c.id=ci.cart_id WHERE c.user_id=? ORDER BY ci.id",
                (rs, row) -> rs.getInt(1), userId);
    }

    private User user() {
        return users.saveAndFlush(User.builder().fullName("Course Checkout test")
                .email("course-checkout-" + UUID.randomUUID() + "@example.test").passwordHash("test-hash")
                .role(Role.USER).isActive(true).build());
    }

    private Course course(String name, CourseStatus status, long price, Long discount) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Course course = new Course();
        course.setCode("CO-" + suffix);
        course.setSlug("course-checkout-" + name + "-" + suffix);
        course.setTitle("Checkout " + name);
        course.setPrice(price);
        course.setDiscountPrice(discount);
        course.setLevel(CourseLevel.BASIC);
        course.setStatus(status);
        course.setPublishedAt(status == CourseStatus.PUBLISHED ? LocalDateTime.now() : null);
        return courses.saveAndFlush(course);
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private String checkoutBody(long revision, List<Integer> cartItemIds, String voucherCode) {
        String selections = cartItemIds.stream().map(id -> "{\"cartItemId\":" + id + "}")
                .collect(java.util.stream.Collectors.joining(","));
        String voucher = voucherCode == null ? "null" : "\"" + voucherCode + "\"";
        return "{\"cartRevision\":" + revision + ",\"selections\":[" + selections
                + "],\"voucherCode\":" + voucher + "}";
    }

    private record CartLine(Integer cartItemId, long revision) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClaimFailureConfiguration {
        @Bean ClaimFailureControl claimFailureControl() { return new ClaimFailureControl(); }

        @Bean @Primary
        CoursePurchasePort testCoursePurchasePort(CoursePurchasePortAdapter actual, ClaimFailureControl control) {
            return new CoursePurchasePort() {
                @Override public boolean isOwnedBy(Integer user, Integer course) {
                    return actual.isOwnedBy(user, course);
                }
                @Override public boolean hasActivePendingPurchase(Integer user, Integer course, Instant at) {
                    return actual.hasActivePendingPurchase(user, course, at);
                }
                @Override public void claimForOrder(Integer user, Integer course, Integer order, Integer item,
                                                    Instant at, Instant expires) {
                    if (course.equals(control.failCourseId)) {
                        throw new CourseClaimConflictException(
                                CourseClaimConflictException.Reason.PENDING_IN_ANOTHER_ORDER,
                                course.longValue(), "Concurrent Course claim conflict");
                    }
                    actual.claimForOrder(user, course, order, item, at, expires);
                }
                @Override public void confirmForOrder(Integer user, Integer course, Integer order, Instant at) {
                    actual.confirmForOrder(user, course, order, at);
                }
                @Override public void releaseForOrder(Integer user, Integer course, Integer order,
                                                      String reason, Instant at) {
                    actual.releaseForOrder(user, course, order, reason, at);
                }
            };
        }
    }

    static final class ClaimFailureControl { volatile Integer failCourseId; }
}
