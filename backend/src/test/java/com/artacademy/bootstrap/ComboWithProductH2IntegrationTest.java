package com.artacademy.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.bundle.BundleItem;
import com.artacademy.bundle.BundleItemRepository;
import com.artacademy.bundle.BundleRepository;
import com.artacademy.bundle.CourseBundle;
import com.artacademy.course.Course;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.payment.vnpay.IpnClassification;
import com.artacademy.payment.vnpay.VerifiedVnPayCallback;
import com.artacademy.payment.vnpay.VnPayCallbackVerifier;
import com.artacademy.payment.vnpay.VnPayIpnReconciliationService;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.artacademy.supply.catalog.CatalogDtos.CategoryRequest;
import com.artacademy.supply.catalog.CatalogDtos.ProductRequest;
import com.artacademy.supply.catalog.CatalogService;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.PaymentAttempt;
import com.drawingcourse.backend.entity.PaymentAttemptJpaEntity;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
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
 * Một đơn có cả COMBO lẫn học cụ (PRODUCT) qua giỏ chung và Product Checkout: giữ hàng + giữ chỗ từng khóa trong cùng
 * một transaction, thanh toán xong vừa tạo giao hàng vừa cấp quyền học đủ khóa, hết hạn thì trả cả hàng lẫn chỗ khóa.
 */
@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.cart.api-enabled=true",
        "app.checkout.course-only.enabled=true",
        "app.checkout.course-only.catalog-adapter.enabled=true",
        "app.checkout.product.enabled=true",
        "app.checkout.product.http-enabled=true",
        "app.checkout.shipping.flat-fee-vnd=15000",
        "app.order.expiration.enabled=true",
        "app.payment.vnpay.enabled=true",
        "app.payment.vnpay.tmn-code=DEMOV210",
        "app.payment.vnpay.secret-key=combo-product-test-secret",
        "app.payment.vnpay.return-url=https://merchant.example.test/return",
        "app.payment.vnpay.ipn-url=https://merchant.example.test/ipn",
        "app.payment.settlement.enabled=true",
        "app.payment.settlement.worker.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:combo_with_product_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ComboWithProductH2IntegrationTest.ClockConfiguration.class)
class ComboWithProductH2IntegrationTest {
    private static final String SECRET = "combo-product-test-secret";
    private static final java.util.concurrent.atomic.AtomicInteger TRANSACTION_IDS =
            new java.util.concurrent.atomic.AtomicInteger(66000000);
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final String ADDRESS = "{\"recipient\":\"Test Buyer\",\"phone\":\"0900000000\","
            + "\"addressLine\":\"1 Test Street\",\"locality\":\"District 1\",\"postalCode\":\"700000\","
            + "\"countryCode\":\"VN\"}";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private BundleRepository bundles;
    @Autowired private BundleItemRepository bundleItems;
    @Autowired private CatalogService catalog;
    @Autowired private OrderRepository orders;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private JwtService jwt;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private VnPayCallbackVerifier verifier;
    @Autowired private VnPayIpnReconciliationService reconciliation;
    @Autowired private SettlementWorkClaimService workClaims;
    @Autowired private SettlementCoordinator settlement;
    @Autowired private OrderExpirationCoordinator expiration;
    @Autowired private TestClock clock;

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary TestClock comboProductClock() { return new TestClock(); }
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

    // ------------------------------------------------------------------ giỏ chung

    @Test
    void generalCartHoldsAComboAlongsideAProduct_andFlagsAnOverlappingCourse() throws Exception {
        Combo combo = combo(220_000);
        long productId = product(50_000, 10);
        User buyer = user();
        String auth = bearer(buyer);

        addToCart(auth, "COMBO", combo.id, 1, 200);
        JsonNode cart = addToCart(auth, "PRODUCT", (int) productId, 2, 200);

        JsonNode items = cart.path("data").path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("itemType").asText()).isEqualTo("COMBO");
        assertThat(items.get(0).path("availabilityStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(items.get(1).path("itemType").asText()).isEqualTo("PRODUCT");
        assertThat(items.get(1).path("quantity").asInt()).isEqualTo(2);
        assertThat(cart.path("data").path("subtotalVnd").asLong()).isEqualTo(220_000L + 100_000L);
        assertThat(cart.path("data").path("canCheckout").asBoolean()).isTrue();

        // combo luôn một dòng số lượng 1
        addToCart(auth, "COMBO", combo.id, 2, 400);

        // thêm một khóa nằm trong combo: dòng đến sau bị báo trùng
        JsonNode overlap = addToCart(auth, "COURSE", combo.courseIds.getFirst(), 1, 200);
        JsonNode last = overlap.path("data").path("items").get(2);
        assertThat(last.path("itemType").asText()).isEqualTo("COURSE");
        assertThat(last.path("availabilityStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(overlap.path("data").path("canCheckout").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ đặt hàng → thanh toán → cấp quyền + giao hàng

    @Test
    void mixedComboAndProductOrder_reservesStockAndCourses_thenPaymentGrantsAndShips() throws Exception {
        Combo combo = combo(220_000);
        long productId = product(50_000, 10);
        User buyer = user();
        String auth = bearer(buyer);
        addToCart(auth, "COMBO", combo.id, 1, 200);
        JsonNode cart = addToCart(auth, "PRODUCT", (int) productId, 2, 200);
        List<Integer> lineIds = cartItemIds(buyer.getId());
        long revision = cart.path("data").path("revision").asLong();

        int orderId = checkout(auth, "mixed-" + UUID.randomUUID(), revision, lineIds, cart, 201);

        // Order: một dòng COMBO (không voucher) + một dòng PRODUCT, phí giao hàng cố định
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT item_type,item_id,quantity,unit_price_vnd,voucher_eligible,net_amount_vnd FROM order_items "
                        + "WHERE order_id=? ORDER BY line_position", orderId);
        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(l -> l.get("item_type")).containsExactlyInAnyOrder("COMBO", "PRODUCT");
        Map<String, Object> comboLine = lines.stream().filter(l -> l.get("item_type").equals("COMBO")).findFirst().orElseThrow();
        assertThat(((Number) comboLine.get("unit_price_vnd")).longValue()).isEqualTo(220_000L);
        assertThat(((Number) comboLine.get("quantity")).intValue()).isEqualTo(1);
        assertThat(comboLine.get("voucher_eligible")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT total_vnd FROM orders WHERE id=?", Long.class, orderId))
                .isEqualTo(220_000L + 100_000L + 15_000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_shipping_addresses WHERE order_id=?",
                Integer.class, orderId)).isEqualTo(1);

        // C giữ chỗ từng khóa của combo; E giữ hàng của học cụ
        assertThat(jdbc.queryForList("SELECT source_type FROM course_claims WHERE order_id=?", String.class, orderId))
                .containsExactly("COMBO_COMPONENT", "COMBO_COMPONENT");
        assertThat(jdbc.queryForObject("SELECT stock_reserved FROM products WHERE id=?", Integer.class, productId))
                .isEqualTo(2);

        // thanh toán thành công
        Order domain = new TransactionTemplate(transactions).execute(s ->
                new OrderPersistenceMapper().toDomain(orders.findByIdAndUserId(orderId, buyer.getId()).orElseThrow()));
        Instant created = domain.getCreatedAt();
        sleepUntil(created.plusSeconds(4));
        clock.set(Instant.now());
        PaymentRef payment = pendingPayment(orderId, domain, created);
        var reconciled = reconciliation.reconcile(signedSuccess(payment, created.plusSeconds(3)));
        assertThat(reconciled.classification()).isEqualTo(IpnClassification.VALID_ON_TIME_SUCCESS);
        var lease = workClaims.claimNext("combo-product-test").orElseThrow();

        SettlementResult result = settlement.executeClaimedWork(lease);

        assertThat(result.outcome()).isEqualTo(SettlementResult.Outcome.SETTLED);
        assertThat(orders.findByIdAndUserId(orderId, buyer.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        // quyền học đủ mọi khóa trong combo
        assertThat(jdbc.queryForList("SELECT status FROM course_claims WHERE order_id=?", String.class, orderId))
                .containsExactly("CONFIRMED", "CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?",
                Integer.class, orderId)).isEqualTo(2);
        // học cụ được tạo chuyến giao hàng cho đúng đơn này
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, orderId))
                .isPositive();

        // chạy lại settlement: không cấp thêm, không tạo thêm chuyến giao
        int shipments = jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, orderId);
        assertThat(settlement.executeClaimedWork(lease).outcome()).isEqualTo(SettlementResult.Outcome.ALREADY_SETTLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?",
                Integer.class, orderId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, orderId))
                .isEqualTo(shipments);
    }

    @Test
    void comboOnlyOrderNeedsNoAddress_andAMixedOneDoes() throws Exception {
        Combo combo = combo(220_000);
        long productId = product(50_000, 10);
        User buyer = user();
        String auth = bearer(buyer);
        addToCart(auth, "COMBO", combo.id, 1, 200);
        JsonNode cart = addToCart(auth, "PRODUCT", (int) productId, 1, 200);
        long revision = cart.path("data").path("revision").asLong();
        List<Integer> lineIds = cartItemIds(buyer.getId());

        // có học cụ mà thiếu địa chỉ giao hàng: bị từ chối và không ghi gì
        mvc.perform(post("/api/checkout").header("Authorization", auth)
                        .header("Idempotency-Key", "noaddr-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(revision, lineIds, cart, false)))
                .andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_claims WHERE user_id=?", Integer.class, buyer.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT stock_reserved FROM products WHERE id=?", Integer.class, productId)).isZero();

        // đơn chỉ có combo thì không cần địa chỉ và không có phí giao hàng
        User other = user();
        String otherAuth = bearer(other);
        JsonNode comboOnly = addToCart(otherAuth, "COMBO", combo.id, 1, 200);
        int orderId = checkout(otherAuth, "combo-only-" + UUID.randomUUID(), comboOnly.path("data").path("revision").asLong(),
                cartItemIds(other.getId()), comboOnly, 201, false);
        assertThat(jdbc.queryForObject("SELECT total_vnd FROM orders WHERE id=?", Long.class, orderId)).isEqualTo(220_000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_shipping_addresses WHERE order_id=?", Integer.class, orderId))
                .isZero();
    }

    @Test
    void outOfStockProductRollsBackTheComboClaimsToo() throws Exception {
        Combo combo = combo(220_000);
        long productId = product(50_000, 1);
        User buyer = user();
        String auth = bearer(buyer);
        addToCart(auth, "COMBO", combo.id, 1, 200);
        JsonNode cart = addToCart(auth, "PRODUCT", (int) productId, 1, 200);
        long revision = cart.path("data").path("revision").asLong();
        List<Integer> lineIds = cartItemIds(buyer.getId());
        // người khác mua mất món cuối cùng trước khi khách đặt hàng
        jdbc.update("UPDATE products SET stock_reserved = stock_on_hand WHERE id=?", productId);

        mvc.perform(post("/api/checkout").header("Authorization", auth)
                        .header("Idempotency-Key", "oos-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(revision, lineIds, cart, true)))
                .andExpect(status().is4xxClientError());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_claims WHERE user_id=?", Integer.class, buyer.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?", Integer.class, buyer.getId()))
                .isZero();
    }

    // ------------------------------------------------------------------ hết hạn

    @Test
    void expiredMixedOrderReleasesTheProductStockAndEveryCourseClaim() throws Exception {
        Combo combo = combo(220_000);
        long productId = product(50_000, 10);
        User buyer = user();
        String auth = bearer(buyer);
        addToCart(auth, "COMBO", combo.id, 1, 200);
        JsonNode cart = addToCart(auth, "PRODUCT", (int) productId, 3, 200);
        int orderId = checkout(auth, "expire-" + UUID.randomUUID(), cart.path("data").path("revision").asLong(),
                cartItemIds(buyer.getId()), cart, 201);
        assertThat(jdbc.queryForObject("SELECT stock_reserved FROM products WHERE id=?", Integer.class, productId))
                .isEqualTo(3);

        // quá hạn thanh toán (đồng hồ của ứng dụng đi qua 31 phút)
        clock.set(Instant.now().plus(31, ChronoUnit.MINUTES));
        ExpirationResult first = expiration.expire(orderId);
        ExpirationResult duplicate = expiration.expire(orderId);

        assertThat(first.outcome()).isEqualTo(ExpirationResult.Outcome.EXPIRED);
        assertThat(duplicate.outcome()).isEqualTo(ExpirationResult.Outcome.ALREADY_EXPIRED);
        assertThat(orders.findByIdAndUserId(orderId, buyer.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(jdbc.queryForList("SELECT status FROM course_claims WHERE order_id=?", String.class, orderId))
                .containsExactly("RELEASED", "RELEASED");
        assertThat(jdbc.queryForObject("SELECT stock_reserved FROM products WHERE id=?", Integer.class, productId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?",
                Integer.class, orderId)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private record Combo(Integer id, String title, List<Integer> courseIds) { }

    private Combo combo(long price) {
        Course first = course("a", 100_000, null);
        Course second = course("b", 200_000, 150_000L);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        CourseBundle bundle = new CourseBundle();
        bundle.setCode("CBP-" + suffix);
        bundle.setTitle("Combo mixed " + suffix);
        bundle.setPrice(price);
        bundle = bundles.saveAndFlush(bundle);
        bundleItems.saveAndFlush(new BundleItem(bundle, first, 1));
        bundleItems.saveAndFlush(new BundleItem(bundle, second, 2));
        return new Combo(bundle.getId(), bundle.getTitle(), List.of(first.getId(), second.getId()));
    }

    private Course course(String name, long price, Long discount) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Course course = new Course();
        course.setCode("CMP-" + suffix);
        course.setSlug("combo-mixed-" + name + "-" + suffix);
        course.setTitle("Combo mixed " + name);
        course.setPrice(price);
        course.setDiscountPrice(discount);
        course.setLevel(CourseLevel.BASIC);
        course.setStatus(CourseStatus.PUBLISHED);
        course.setPublishedAt(LocalDateTime.now());
        return courses.saveAndFlush(course);
    }

    private long product(long price, int stock) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Long categoryId = catalog.createCategory(new CategoryRequest("Mixed " + suffix, "test", true, 1)).id();
        return catalog.createProduct(new ProductRequest(categoryId, "MIX-" + suffix, "Mixed product " + suffix,
                "test product", price, null, 5, stock, true), null).id();
    }

    private User user() {
        return users.saveAndFlush(User.builder().fullName("Combo product test")
                .email("combo-product-" + UUID.randomUUID() + "@example.test").passwordHash("test-hash")
                .role(Role.USER).isActive(true).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private JsonNode addToCart(String auth, String type, Integer itemId, int quantity, int expectedStatus) throws Exception {
        var response = mvc.perform(post("/api/cart/items").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"" + type + "\",\"itemId\":" + itemId + ",\"quantity\":" + quantity + "}"))
                .andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(response.getResponse().getContentAsString());
    }

    private int checkout(String auth, String key, long revision, List<Integer> lineIds, JsonNode cart, int expectedStatus)
            throws Exception {
        return checkout(auth, key, revision, lineIds, cart, expectedStatus, true);
    }

    private int checkout(String auth, String key, long revision, List<Integer> lineIds, JsonNode cart,
                         int expectedStatus, boolean withAddress) throws Exception {
        var response = mvc.perform(post("/api/checkout").header("Authorization", auth)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(revision, lineIds, cart, withAddress)))
                .andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data").path("orderId").asInt();
    }

    /** Mỗi lựa chọn gửi cartItemId và đúng số lượng đang có trong giỏ (combo/khóa luôn 1). */
    private String checkoutBody(long revision, List<Integer> lineIds, JsonNode cart, boolean withAddress) {
        Map<Integer, Integer> quantities = new LinkedHashMap<>();
        for (JsonNode item : cart.path("data").path("items")) {
            quantities.put(item.path("cartItemId").asInt(), item.path("quantity").asInt());
        }
        String selections = lineIds.stream()
                .map(id -> "{\"cartItemId\":" + id + ",\"quantity\":" + quantities.getOrDefault(id, 1) + "}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"cartRevision\":" + revision + ",\"selections\":[" + selections + "],\"voucherCode\":null"
                + (withAddress ? ",\"address\":" + ADDRESS : "") + "}";
    }

    private List<Integer> cartItemIds(Integer userId) {
        return jdbc.query("SELECT ci.id FROM cart_items ci JOIN carts c ON c.id=ci.cart_id WHERE c.user_id=? ORDER BY ci.id",
                (rs, row) -> rs.getInt(1), userId);
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
        PaymentAttempt attempt = PaymentAttempt.create(orderId, domain, "vnpay", "combo-prod-" + orderId,
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
        values.put("vnp_OrderInfo", "verified combo and product test");
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
