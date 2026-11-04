package com.artacademy.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.bundle.BundleItem;
import com.artacademy.bundle.BundleItemRepository;
import com.artacademy.bundle.BundleRepository;
import com.artacademy.bundle.CourseBundle;
import com.artacademy.course.Course;
import com.artacademy.course.CourseIncludedKit;
import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.claim.CourseClaimService;
import com.artacademy.payment.vnpay.IpnClassification;
import com.artacademy.payment.vnpay.VerifiedVnPayCallback;
import com.artacademy.payment.vnpay.VnPayCallbackVerifier;
import com.artacademy.payment.vnpay.VnPayIpnReconciliationService;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.entity.CartJpaEntity;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderJpaEntity;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.PaymentAttempt;
import com.drawingcourse.backend.entity.PaymentAttemptJpaEntity;
import com.drawingcourse.backend.repository.CartRepository;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentSettlementRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.OrderPersistenceService;
import com.drawingcourse.backend.service.checkout.ComboCatalogPort;
import com.drawingcourse.backend.service.checkout.ComboPurchasePort;
import com.drawingcourse.backend.service.checkout.CoursePurchasePort;
import com.drawingcourse.backend.service.expiration.ExpirationResult;
import com.drawingcourse.backend.service.expiration.OrderExpirationCoordinator;
import com.drawingcourse.backend.service.settlement.SettlementCoordinator;
import com.drawingcourse.backend.service.settlement.SettlementResult;
import com.drawingcourse.backend.service.settlement.SettlementWorkClaimService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


/**
 * Mua combo từ đầu đến cuối với B/C/D thật: giỏ (Course-only) nhận combo, checkout tạo MỘT dòng đơn và giữ chỗ từng
 * khóa, thanh toán thành công cấp quyền học đủ mọi khóa, đơn hết hạn thì nhả chỗ mọi khóa.
 */
@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.cart.course-only.api-enabled=true",
        "app.checkout.course-only.enabled=true",
        "app.checkout.course-only.http-enabled=true",
        "app.checkout.course-only.catalog-adapter.enabled=true",
        "app.order.expiration.enabled=true",
        "app.payment.vnpay.enabled=true",
        "app.payment.vnpay.tmn-code=DEMOV210",
        "app.payment.vnpay.secret-key=combo-purchase-test-secret",
        "app.payment.vnpay.return-url=https://merchant.example.test/return",
        "app.payment.vnpay.ipn-url=https://merchant.example.test/ipn",
        "app.payment.settlement.enabled=true",
        "app.payment.settlement.worker.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:combo_purchase_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ComboPurchaseH2IntegrationTest.ClockConfiguration.class)
class ComboPurchaseH2IntegrationTest {
    private static final String SECRET = "combo-purchase-test-secret";
    private static final java.util.concurrent.atomic.AtomicInteger TRANSACTION_IDS =
            new java.util.concurrent.atomic.AtomicInteger(77000000);
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private CourseKitRepository kits;
    @Autowired private BundleRepository bundles;
    @Autowired private BundleItemRepository bundleItems;
    @Autowired private CartRepository carts;
    @Autowired private OrderRepository orders;
    @Autowired private com.drawingcourse.backend.repository.OrderItemRepository orderItemRepository;
    @Autowired private CourseClaimService claims;
    @Autowired private ComboPurchasePort comboPurchases;
    @Autowired private CoursePurchasePort coursePurchases;
    @Autowired private ComboCatalogPort comboCatalog;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private JwtService jwt;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private VnPayCallbackVerifier verifier;
    @Autowired private VnPayIpnReconciliationService reconciliation;
    @Autowired private SettlementWorkClaimService workClaims;
    @Autowired private SettlementCoordinator settlement;
    @Autowired private PaymentSettlementRepository settlements;
    @Autowired private OrderExpirationCoordinator expiration;
    @Autowired private TestClock clock;

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary TestClock comboPurchaseClock() { return new TestClock(); }
    }

    static class TestClock extends Clock {
        private Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        void set(Instant value) { now = value.truncatedTo(ChronoUnit.MICROS); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @BeforeEach
    void resetClock() {
        clock.set(Instant.now());
    }

    // ------------------------------------------------------------------ giỏ hàng

    @Test
    void cartAcceptsAComboAsOneLine_idempotently_andRejectsBadQuantity() throws Exception {
        Combo combo = combo(220_000);
        User buyer = user();
        String auth = bearer(buyer);

        JsonNode added = addToCart(auth, "COMBO", combo.id, 200);
        JsonNode item = added.path("data").path("items").get(0);
        assertThat(item.path("itemType").asText()).isEqualTo("COMBO");
        assertThat(item.path("itemId").asInt()).isEqualTo(combo.id);
        assertThat(item.path("quantity").asInt()).isEqualTo(1);
        assertThat(item.path("displayName").asText()).isEqualTo(combo.title);
        assertThat(item.path("availabilityStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(item.path("unitPriceVnd").asLong()).isEqualTo(220_000L);
        assertThat(added.path("data").path("subtotalVnd").asLong()).isEqualTo(220_000L);
        assertThat(added.path("data").path("canCheckout").asBoolean()).isTrue();
        long revision = added.path("data").path("revision").asLong();

        // thêm lại cùng combo: không tạo dòng mới, không đổi phiên bản giỏ
        JsonNode again = addToCart(auth, "COMBO", combo.id, 200);
        assertThat(again.path("data").path("items")).hasSize(1);
        assertThat(again.path("data").path("revision").asLong()).isEqualTo(revision);

        // combo luôn số lượng 1
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"COMBO\",\"itemId\":" + combo.id + ",\"quantity\":2}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(patch("/api/cart/items/" + item.path("cartItemId").asInt()).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/cart/items/" + item.path("cartItemId").asInt()).header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"PRODUCT\",\"itemId\":1,\"quantity\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void cartRejectsUnsafeCombos() throws Exception {
        User buyer = user();
        String auth = bearer(buyer);

        assertThat(addToCart(auth, "COMBO", 999_999, 409).path("message").asText())
                .isEqualTo("Combo này hiện không bán được."); // không tồn tại

        Combo inactive = combo(220_000);
        CourseBundle row = bundles.findById(inactive.id).orElseThrow();
        row.setActive(false);
        bundles.saveAndFlush(row);
        assertThat(addToCart(auth, "COMBO", inactive.id, 409).path("message").asText())
                .isEqualTo("Combo này hiện không bán được."); // đã tắt

        Combo withGift = combo(220_000);
        kits.saveAndFlush(new CourseIncludedKit(courses.findById(withGift.courseIds.getFirst()).orElseThrow(), 999_999, 1));
        // có khóa kèm quà học cụ thật: chưa giao quà được, và thông báo nói rõ lý do
        assertThat(addToCart(auth, "COMBO", withGift.id, 409).path("message").asText())
                .contains("quà tặng").contains("học cụ");
        // cùng lý do với khóa lẻ có quà tặng kèm
        assertThat(addToCart(auth, "COURSE", withGift.courseIds.getFirst(), 409).path("message").asText())
                .contains("quà tặng").contains("học cụ");

        Combo pending = combo(220_000);
        seedPendingClaim(buyer, pending.courseIds.getLast());
        // một khóa trong combo đang chờ thanh toán ở đơn khác
        assertThat(addToCart(auth, "COMBO", pending.id, 409).path("message").asText()).contains("chờ thanh toán");
        assertThat(addToCart(auth, "COURSE", pending.courseIds.getLast(), 409).path("message").asText())
                .contains("chờ thanh toán");

        assertThat(cartRows(buyer.getId())).isZero();
    }

    @Test
    void cartFlagsAComboThatOverlapsAnotherLine() throws Exception {
        Combo combo = combo(220_000);
        User buyer = user();
        String auth = bearer(buyer);
        addToCart(auth, "COMBO", combo.id, 200);

        JsonNode both = addToCart(auth, "COURSE", combo.courseIds.getFirst(), 200);

        JsonNode items = both.path("data").path("items");
        assertThat(items.get(0).path("itemType").asText()).isEqualTo("COMBO");
        assertThat(items.get(0).path("availabilityStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(items.get(1).path("availabilityStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(both.path("data").path("canCheckout").asBoolean()).isFalse();
        assertThat(both.path("data").path("subtotalVnd").asLong()).isEqualTo(220_000L);
    }

    // ------------------------------------------------------------------ đặt hàng → thanh toán → cấp quyền học

    @Test
    void comboCheckoutCreatesOneOrderLineAndReservesEveryCourse_thenPaymentGrantsAllCourses() throws Exception {
        Combo combo = combo(220_000);
        User buyer = user();
        String auth = bearer(buyer);
        JsonNode cart = addToCart(auth, "COMBO", combo.id, 200);
        int cartItemId = cart.path("data").path("items").get(0).path("cartItemId").asInt();
        long revision = cart.path("data").path("revision").asLong();
        String key = "combo-" + UUID.randomUUID();

        int orderId = checkout(auth, key, revision, List.of(cartItemId), 201);

        // Order: đúng MỘT dòng COMBO, giá của combo, không áp voucher
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id=?", Integer.class, orderId))
                .isEqualTo(1);
        Map<String, Object> line = jdbc.queryForMap(
                "SELECT id,item_type,item_id,quantity,unit_price_vnd,voucher_eligible,net_amount_vnd,name_snapshot "
                        + "FROM order_items WHERE order_id=?", orderId);
        int orderItemId = ((Number) line.get("id")).intValue();
        assertThat(line.get("item_type")).isEqualTo("COMBO");
        assertThat(((Number) line.get("item_id")).intValue()).isEqualTo(combo.id);
        assertThat(((Number) line.get("quantity")).intValue()).isEqualTo(1);
        assertThat(((Number) line.get("unit_price_vnd")).longValue()).isEqualTo(220_000L);
        assertThat(((Number) line.get("net_amount_vnd")).longValue()).isEqualTo(220_000L);
        assertThat(line.get("voucher_eligible")).isEqualTo(false);
        assertThat(line.get("name_snapshot")).isEqualTo(combo.title);
        assertThat(jdbc.queryForObject("SELECT total_vnd FROM orders WHERE id=?", Long.class, orderId))
                .isEqualTo(220_000L);

        // C: mỗi khóa trong combo có một claim RESERVED, gắn với OrderItem combo và vị trí khóa
        List<Map<String, Object>> reserved = jdbc.queryForList(
                "SELECT course_id,status,source_type,component_position,order_item_id FROM course_claims "
                        + "WHERE order_id=? ORDER BY component_position", orderId);
        assertThat(reserved).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(((Number) reserved.get(i).get("course_id")).intValue()).isEqualTo(combo.courseIds.get(i));
            assertThat(reserved.get(i).get("status")).isEqualTo("RESERVED");
            assertThat(reserved.get(i).get("source_type")).isEqualTo("COMBO_COMPONENT");
            assertThat(((Number) reserved.get(i).get("component_position")).intValue()).isEqualTo(i + 1);
            assertThat(((Number) reserved.get(i).get("order_item_id")).intValue()).isEqualTo(orderItemId);
        }

        // gửi lại đúng yêu cầu: trả lại đơn cũ, không tạo thêm đơn hay claim
        assertThat(checkout(auth, key, revision, List.of(cartItemId), 200)).isEqualTo(orderId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_claims WHERE order_id=?", Integer.class, orderId))
                .isEqualTo(2);

        // đang chờ thanh toán: giỏ báo combo không khả dụng, và không thêm được từng khóa trong combo
        assertThat(cartLine(auth, 0).path("availabilityStatus").asText()).isEqualTo("UNAVAILABLE");
        addToCart(auth, "COURSE", combo.courseIds.getFirst(), 409);

        // thanh toán thành công (VNPay đã xác minh) -> cấp quyền học cho CẢ HAI khóa
        Order domain = new TransactionTemplate(transactions).execute(s ->
                new OrderPersistenceMapper().toDomain(orders.findByIdAndUserId(orderId, buyer.getId()).orElseThrow()));
        Instant created = domain.getCreatedAt();
        // Mốc thanh toán phải nằm sau thời điểm tạo đơn nhưng không vượt quá giờ thật của DB (work item chỉ nhận được
        // khi đến hạn): đợi vài giây rồi lấy giờ thật làm đồng hồ.
        sleepUntil(created.plusSeconds(4));
        clock.set(Instant.now());
        PaymentRef payment = pendingPayment(orderId, domain, created);
        var reconciled = reconciliation.reconcile(signedSuccess(payment, created.plusSeconds(3)));
        assertThat(reconciled.classification()).isEqualTo(IpnClassification.VALID_ON_TIME_SUCCESS);
        var lease = workClaims.claimNext("combo-purchase-test").orElseThrow();

        SettlementResult result = settlement.executeClaimedWork(lease);

        assertThat(result.outcome()).isEqualTo(SettlementResult.Outcome.SETTLED);
        assertThat(orders.findByIdAndUserId(orderId, buyer.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForList("SELECT status FROM course_claims WHERE order_id=?", String.class, orderId))
                .containsExactly("CONFIRMED", "CONFIRMED");
        List<Map<String, Object>> enrollments = jdbc.queryForList(
                "SELECT course_id,status,source_order_id,source_order_item_id FROM user_course_enrollments "
                        + "WHERE source_order_id=? ORDER BY course_id", orderId);
        assertThat(enrollments).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(((Number) enrollments.get(i).get("course_id")).intValue()).isEqualTo(combo.courseIds.get(i));
            assertThat(enrollments.get(i).get("status")).isEqualTo("ACTIVE");
            assertThat(((Number) enrollments.get(i).get("source_order_item_id")).intValue()).isEqualTo(orderItemId);
        }
        assertThat(settlements.findByOrderId(orderId).stream().count()).isEqualTo(1);

        // chạy lại settlement: không cấp thêm quyền, không ghi sổ thêm
        assertThat(settlement.executeClaimedWork(lease).outcome()).isEqualTo(SettlementResult.Outcome.ALREADY_SETTLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?",
                Integer.class, orderId)).isEqualTo(2);

        // đã sở hữu: combo trong giỏ báo không khả dụng, từng khóa cũng không thêm lại được
        assertThat(cartLine(auth, 0).path("availabilityStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(addToCart(auth, "COURSE", combo.courseIds.getLast(), 409).path("message").asText())
                .isEqualTo("Bạn đã sở hữu khóa học này rồi.");
        Combo again = new Combo(combo.id, combo.title, combo.courseIds);
        User other = user();
        assertThat(addToCart(bearer(other), "COMBO", again.id, 200).path("data").path("items").get(0)
                .path("availabilityStatus").asText()).isEqualTo("AVAILABLE"); // người khác vẫn mua được
    }

    @Test
    void checkoutRejectsAComboOverlappingACourse_andWritesNothing() throws Exception {
        Combo combo = combo(220_000);
        User buyer = user();
        CartJpaEntity cart = new CartJpaEntity(buyer.getId());
        cart.addItem(CartItemType.COMBO, combo.id, 1);
        cart.addItem(CartItemType.COURSE, combo.courseIds.getFirst(), 1);
        carts.saveAndFlush(cart);

        mvc.perform(post("/api/checkout/course-only").header("Authorization", bearer(buyer))
                        .header("Idempotency-Key", "overlap-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutBody(0, cartItemIds(buyer.getId()))))
                .andExpect(status().isConflict());

        assertNothingWritten(buyer);
    }

    @Test
    void checkoutOfAComboWhoseCourseIsAlreadyPending_rollsBackEverything() throws Exception {
        Combo combo = combo(220_000);
        User buyer = user();
        seedPendingClaim(buyer, combo.courseIds.getLast());
        int ordersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.getId());
        CartJpaEntity cart = new CartJpaEntity(buyer.getId());
        cart.addItem(CartItemType.COMBO, combo.id, 1);
        carts.saveAndFlush(cart);

        mvc.perform(post("/api/checkout/course-only").header("Authorization", bearer(buyer))
                        .header("Idempotency-Key", "pending-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutBody(0, cartItemIds(buyer.getId()))))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.getId()))
                .isEqualTo(ordersBefore);
        // chỉ còn claim của đơn khác (khóa kia); combo không giữ được khóa nào
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_claims WHERE user_id=?", Integer.class, buyer.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?", Integer.class, buyer.getId()))
                .isZero();
    }

    @Test
    void comboPriceIsReadAtCheckout_notFromTheCart() throws Exception {
        Combo combo = combo(220_000);
        User buyer = user();
        String auth = bearer(buyer);
        JsonNode cart = addToCart(auth, "COMBO", combo.id, 200);
        // admin đổi giá combo sau khi khách thêm vào giỏ
        CourseBundle row = bundles.findById(combo.id).orElseThrow();
        row.setPrice(200_000);
        bundles.saveAndFlush(row);

        int orderId = checkout(auth, "price-" + UUID.randomUUID(), cart.path("data").path("revision").asLong(),
                List.of(cart.path("data").path("items").get(0).path("cartItemId").asInt()), 201);

        assertThat(jdbc.queryForObject("SELECT total_vnd FROM orders WHERE id=?", Long.class, orderId)).isEqualTo(200_000L);
    }

    // ------------------------------------------------------------------ hết hạn thanh toán

    @Test
    void expiredComboOrderReleasesEveryCourseClaim_andNothingIsGranted() {
        Combo combo = combo(220_000);
        User buyer = user();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant created = now.minusSeconds(31 * 60L);
        Integer orderId = new TransactionTemplate(transactions).execute(status -> {
            Order snapshot = Order.create(buyer.getId(), List.of(new OrderLineSnapshot(OrderItemType.COMBO, combo.id,
                    combo.title, 1, 220_000, false)), 0, Optional.empty(), Clock.fixed(created, ZoneOffset.UTC));
            OrderJpaEntity order = OrderJpaEntity.fromSnapshot(snapshot);
            em.persist(order);
            em.flush();
            comboPurchases.claimComboForOrder(buyer.getId(), order.getId(), order.getItems().getFirst().getId(),
                    comboCatalog.findForCheckout(combo.id).orElseThrow().components(), created, snapshot.getExpiresAt());
            return order.getId();
        });
        assertThat(jdbc.queryForList("SELECT status FROM course_claims WHERE order_id=?", String.class, orderId))
                .containsExactly("RESERVED", "RESERVED");

        ExpirationResult first = expiration.expire(orderId);
        ExpirationResult duplicate = expiration.expire(orderId);

        assertThat(first.outcome()).isEqualTo(ExpirationResult.Outcome.EXPIRED);
        assertThat(duplicate.outcome()).isEqualTo(ExpirationResult.Outcome.ALREADY_EXPIRED);
        assertThat(orders.findByIdAndUserId(orderId, buyer.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(jdbc.queryForList("SELECT status FROM course_claims WHERE order_id=?", String.class, orderId))
                .containsExactly("RELEASED", "RELEASED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?",
                Integer.class, orderId)).isZero();
        // các khóa được nhả nên có thể mua lại
        assertThat(coursePurchases.hasActivePendingPurchase(buyer.getId(), combo.courseIds.getFirst(),
                Instant.now())).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private record Combo(Integer id, String title, List<Integer> courseIds) { }

    private Combo combo(long price) {
        Course first = course("a", 100_000, null);
        Course second = course("b", 200_000, 150_000L);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        CourseBundle bundle = new CourseBundle();
        bundle.setCode("CB-" + suffix);
        bundle.setTitle("Combo " + suffix);
        bundle.setPrice(price);
        bundle = bundles.saveAndFlush(bundle);
        bundleItems.saveAndFlush(new BundleItem(bundle, first, 1));
        bundleItems.saveAndFlush(new BundleItem(bundle, second, 2));
        return new Combo(bundle.getId(), bundle.getTitle(), List.of(first.getId(), second.getId()));
    }

    private Course course(String name, long price, Long discount) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Course course = new Course();
        course.setCode("CP-" + suffix);
        course.setSlug("combo-purchase-" + name + "-" + suffix);
        course.setTitle("Combo purchase " + name);
        course.setPrice(price);
        course.setDiscountPrice(discount);
        course.setLevel(CourseLevel.BASIC);
        course.setStatus(CourseStatus.PUBLISHED);
        course.setPublishedAt(LocalDateTime.now());
        return courses.saveAndFlush(course);
    }

    private User user() {
        return users.saveAndFlush(User.builder().fullName("Combo purchase test")
                .email("combo-purchase-" + UUID.randomUUID() + "@example.test").passwordHash("test-hash")
                .role(Role.USER).isActive(true).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private JsonNode addToCart(String auth, String type, Integer itemId, int expectedStatus) throws Exception {
        var response = mvc.perform(post("/api/cart/items").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"" + type + "\",\"itemId\":" + itemId + ",\"quantity\":1}"))
                .andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(response.getResponse().getContentAsString());
    }

    private JsonNode cartLine(String auth, int index) throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/cart")
                        .header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data").path("items").get(index);
    }

    private int checkout(String auth, String key, long revision, List<Integer> cartItemIds, int expectedStatus)
            throws Exception {
        var response = mvc.perform(post("/api/checkout/course-only").header("Authorization", auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(revision, cartItemIds)))
                .andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data").path("orderId").asInt();
    }

    private String checkoutBody(long revision, List<Integer> cartItemIds) {
        String selections = cartItemIds.stream().map(id -> "{\"cartItemId\":" + id + "}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"cartRevision\":" + revision + ",\"selections\":[" + selections + "],\"voucherCode\":null}";
    }

    private List<Integer> cartItemIds(Integer userId) {
        return jdbc.query("SELECT ci.id FROM cart_items ci JOIN carts c ON c.id=ci.cart_id WHERE c.user_id=? ORDER BY ci.id",
                (rs, row) -> rs.getInt(1), userId);
    }

    private int cartRows(Integer userId) {
        return cartItemIds(userId).size();
    }

    private void assertNothingWritten(User buyer) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_claims WHERE user_id=?", Integer.class, buyer.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?", Integer.class, buyer.getId())).isZero();
    }

    /** Một đơn khóa lẻ khác đang chờ thanh toán và giữ chỗ khóa này cho cùng học viên. */
    private void seedPendingClaim(User buyer, Integer courseId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Course course = courses.findById(courseId).orElseThrow();
        var persistence = new OrderPersistenceService(orders, orderItemRepository, () -> buyer.getId(), transactions);
        Order snapshot = Order.create(buyer.getId(), List.of(new OrderLineSnapshot(OrderItemType.COURSE, courseId,
                course.getTitle(), 1, course.effectivePrice(), true)), 0, Optional.empty(), Clock.fixed(now, ZoneOffset.UTC));
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            var stored = persistence.insertNew(snapshot);
            coursePurchases.claimForOrder(buyer.getId(), courseId, stored.id(), stored.itemIdentities().getFirst().id(),
                    now, snapshot.getExpiresAt());
        });
    }

    private static void sleepUntil(Instant target) {
        try {
            long millis = java.time.Duration.between(Instant.now(), target).toMillis();
            if (millis > 0) Thread.sleep(millis + 50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private record PaymentRef(String merchantReference, long amountVnd) { }

    private PaymentRef pendingPayment(Integer orderId, Order domain, Instant created) {
        PaymentAttempt attempt = PaymentAttempt.create(orderId, domain, "vnpay", "combo-pay-" + orderId,
                Clock.fixed(created.plusSeconds(1), ZoneOffset.UTC));
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            em.persist(PaymentAttemptJpaEntity.newSnapshot(attempt));
            em.flush();
        });
        return new PaymentRef(attempt.merchantReference(), attempt.expectedAmountVnd());
    }

    private VerifiedVnPayCallback signedSuccess(PaymentRef payment, Instant paidAt) {
        Map<String, String> values = new TreeMap<>();
        values.put("vnp_TmnCode", "DEMOV210");
        values.put("vnp_Amount", Long.toString(payment.amountVnd() * 100));
        values.put("vnp_BankCode", "NCB");
        values.put("vnp_OrderInfo", "verified combo purchase test");
        values.put("vnp_TransactionNo", Integer.toString(TRANSACTION_IDS.incrementAndGet()));
        values.put("vnp_ResponseCode", "00");
        values.put("vnp_TransactionStatus", "00");
        values.put("vnp_TxnRef", payment.merchantReference());
        values.put("vnp_PayDate", PAY_DATE.format(paidAt));
        String canonical = values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        values.put("vnp_SecureHash", sign(canonical));
        Map<String, List<String>> multi = new LinkedHashMap<>();
        values.forEach((key, value) -> multi.put(key, List.of(value)));
        return verifier.verify(multi);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
