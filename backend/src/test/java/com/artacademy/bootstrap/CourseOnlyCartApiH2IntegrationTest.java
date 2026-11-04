package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.course.Course;
import com.artacademy.course.CourseIncludedKit;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.course.CourseKitRepository;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.EnrollmentService;
import com.artacademy.learning.claim.CourseClaimService;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts;
import com.artacademy.product.Product;
import com.artacademy.product.ProductRepository;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.controller.CartController;
import com.drawingcourse.backend.controller.CourseOnlyCartController;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.repository.CartItemRepository;
import com.drawingcourse.backend.repository.CartRepository;
import com.drawingcourse.backend.repository.OrderItemRepository;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.service.CartApplicationService;
import com.drawingcourse.backend.service.CartAvailabilityProjector;
import com.drawingcourse.backend.service.CartCatalog;
import com.drawingcourse.backend.service.OrderPersistenceService;
import com.drawingcourse.backend.service.checkout.CheckoutCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.cart.course-only.api-enabled=true",
        "spring.datasource.url=jdbc:h2:mem:course_only_cart_api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseOnlyCartApiH2IntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private CourseKitRepository kits;
    @Autowired private ProductRepository products;
    @Autowired private com.drawingcourse.backend.repository.CartRepository carts;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private CourseClaimService claims;
    @Autowired private EnrollmentService enrollments;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private JwtService jwt;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext context;

    @Test
    void apiIsRegisteredOnlyAsCourseProjectionAndRequiresAuthentication() throws Exception {
        assertThat(context.getBeansOfType(CourseOnlyCartController.class)).hasSize(1);
        assertThat(context.getBeansOfType(CartController.class)).isEmpty();
        assertThat(context.getBeansOfType(CartAvailabilityProjector.class)).isEmpty();
        assertThat(context.getBeansOfType(CartCatalog.class)).hasSize(1);
        assertThat(context.getBeansOfType(CheckoutCoordinator.class)).isEmpty();
        // E's real Product adapter is part of the integrated application, but the gated
        // Course-only Cart composition must not register the general Product projector.
        assertThat(context.getBeansOfType(com.drawingcourse.backend.service.ProductCartCatalogProvider.class)).hasSize(1);
        assertThat(context.getBeansOfType(com.drawingcourse.backend.service.ProductStockAvailabilityProvider.class)).hasSize(1);
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
    }

    @Test
    void trustedCoursePriceProjectsAndCartReadHasNoOrderOrClaimSideEffects() throws Exception {
        User owner = user();
        User other = user();
        Course course = course("trusted", CourseStatus.PUBLISHED, 50_000, 35_000L);
        String bearer = bearer(owner);
        int beforeOrders = count("orders");
        int beforeClaims = count("course_claims");
        int beforeAttempts = count("payment_attempts");
        int beforeSettlements = count("payment_settlements");

        mvc.perform(post("/api/cart/items").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"COURSE\",\"itemId\":" + course.getId()
                                + ",\"quantity\":1,\"userId\":" + other.getId() + ",\"price\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].unitPriceVnd").value(35_000))
                .andExpect(jsonPath("$.data.items[0].displayName").value(course.getTitle()))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.userId").doesNotExist());
        mvc.perform(get("/api/cart").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].unitPriceVnd").value(35_000))
                .andExpect(jsonPath("$.data.subtotalVnd").value(35_000));
        assertThat(count("orders")).isEqualTo(beforeOrders);
        assertThat(count("course_claims")).isEqualTo(beforeClaims);
        assertThat(count("payment_attempts")).isEqualTo(beforeAttempts);
        assertThat(count("payment_settlements")).isEqualTo(beforeSettlements);
        assertThat(carts.findByUserId(other.getId())).isEmpty();
    }

    @Test
    void addAndRemoveMutateExistingCartAndIncrementRevision() throws Exception {
        User owner = user();
        Course course = course("mutate", CourseStatus.PUBLISHED, 25_000, null);
        String auth = bearer(owner);
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"COURSE\",\"itemId\":" + course.getId() + ",\"quantity\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        Integer cartItemId = jdbc.queryForObject("SELECT id FROM cart_items WHERE cart_id=(SELECT id FROM carts WHERE user_id=?)",
                Integer.class, owner.getId());
        mvc.perform(delete("/api/cart/items/{id}", cartItemId).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.revision").value(2));
    }

    @Test
    void ownedPendingUnavailableKitAndProductLinesAreRejected() throws Exception {
        User owner = user();
        Course owned = course("owned", CourseStatus.PUBLISHED, 20_000, null);
        Course pending = course("pending", CourseStatus.PUBLISHED, 20_000, null);
        Course kitCourse = course("kit", CourseStatus.PUBLISHED, 20_000, null);
        Course draft = course("draft", CourseStatus.DRAFT, 20_000, null);
        Course badPrice = course("bad-price", CourseStatus.PUBLISHED, 0, null);
        new TransactionTemplate(transactions).executeWithoutResult(s -> enrollments.grantManually(owner.getId(),
                List.of(owned.getId())));
        reservePendingClaim(owner, pending);
        Product physical = products.saveAndFlush(new Product("KIT-" + UUID.randomUUID(), "Included kit", 500, null));
        kits.saveAndFlush(new CourseIncludedKit(kitCourse, physical, 1));
        String auth = bearer(owner);

        assertRejected(auth, owned.getId(), 409);
        assertRejected(auth, pending.getId(), 409);
        assertRejected(auth, kitCourse.getId(), 409);
        assertRejected(auth, draft.getId(), 409);
        assertRejected(auth, Math.toIntExact(draft.getId() + 10_000), 409);
        assertRejected(auth, badPrice.getId(), 503);
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"PRODUCT\",\"itemId\":1,\"quantity\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"COURSE\",\"itemId\":0,\"quantity\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mixedExistingCartIsRejectedByCourseOnlyProjectionAndMutations() throws Exception {
        User owner = user();
        Course course = course("mixed", CourseStatus.PUBLISHED, 30_000, null);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            var cart = new com.drawingcourse.backend.entity.CartJpaEntity(owner.getId());
            cart.addItem(CartItemType.COURSE, Math.toIntExact(course.getId()), 1);
            cart.addItem(CartItemType.PRODUCT, 12, 1);
            carts.saveAndFlush(cart);
        });
        String auth = bearer(owner);
        mvc.perform(get("/api/cart").header("Authorization", auth)).andExpect(status().isConflict());
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"COURSE\",\"itemId\":" + course.getId() + ",\"quantity\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/cart").header("Authorization", auth)).andExpect(status().isConflict());
    }

    private void assertRejected(String auth, Long courseId, int expectedStatus) throws Exception {
        assertRejected(auth, Math.toIntExact(courseId), expectedStatus);
    }

    private void assertRejected(String auth, Integer courseId, int expectedStatus) throws Exception {
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemType\":\"COURSE\",\"itemId\":" + courseId + ",\"quantity\":1}"))
                .andExpect(status().is(expectedStatus));
    }

    private User user() {
        return users.saveAndFlush(User.builder().fullName("Course Cart test")
                .email("course-cart-" + UUID.randomUUID() + "@example.test").passwordHash("test-hash")
                .role(Role.USER).isActive(true).build());
    }

    private Course course(String name, CourseStatus status, long price, Long discount) {
        Course course = new Course();
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        course.setCode("CC-" + random);
        course.setSlug("course-cart-" + name + "-" + random);
        course.setTitle("Course Cart " + name);
        course.setPrice(price);
        course.setDiscountPrice(discount);
        course.setLevel(CourseLevel.BASIC);
        course.setStatus(status);
        return courses.saveAndFlush(course);
    }

    private void reservePendingClaim(User user, Course course) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var orderPersistence = new OrderPersistenceService(orders, orderItems, () -> user.getId(), transactions);
        Order order = Order.create(user.getId(), List.of(new OrderLineSnapshot(OrderItemType.COURSE,
                Math.toIntExact(course.getId()), "Pending Course", 1, 20_000, true)), 0,
                Optional.empty(), Clock.fixed(now, java.time.ZoneOffset.UTC));
        var stored = new TransactionTemplate(transactions).execute(s -> orderPersistence.insertNew(order));
        Integer orderItemId = stored.itemIdentities().getFirst().id();
        new TransactionTemplate(transactions).executeWithoutResult(s -> claims.reserve(
                new CourseClaimContracts.ReservationCommand(user.getId(), stored.id(), List.of(
                        new CourseClaimContracts.ClaimLine(orderItemId, course.getId(),
                                CourseClaimContracts.ClaimSourceType.STANDALONE_COURSE, null)),
                        now, now.plus(30, ChronoUnit.MINUTES))));
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
