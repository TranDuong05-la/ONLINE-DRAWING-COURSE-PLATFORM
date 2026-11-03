package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.payment.vnpay.PaymentSessionApplicationService;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.controller.PaymentSessionController;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.OrderJpaEntity;
import com.drawingcourse.backend.entity.PaymentAttempt;
import com.drawingcourse.backend.entity.PaymentAttemptJpaEntity;
import com.drawingcourse.backend.entity.PaymentStatus;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import com.drawingcourse.backend.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.payment.vnpay.enabled=true",
        "app.payment.vnpay.session-api.enabled=true",
        "app.payment.vnpay.tmn-code=DEMOV210",
        "app.payment.vnpay.secret-key=integration-test-only-secret",
        "app.payment.vnpay.return-url=https://merchant.example.test/return",
        "app.payment.vnpay.ipn-url=https://merchant.example.test/ipn"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PaymentSessionHttpH2IntegrationTest.TestClockConfiguration.class)
class PaymentSessionHttpH2IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private JwtService jwt;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManager entityManager;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean @Primary Clock paymentSessionHttpTestClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @Test
    void routeIsRegisteredOnlyWithBothPaymentServiceAndIndependentHttpGate() {
        assertThat(context.getBeansOfType(PaymentSessionApplicationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(PaymentSessionController.class)).hasSize(1);
    }

    @Test
    void missingAuthorizationIsRejected() throws Exception {
        mvc.perform(post("/api/orders/1/payment-session")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedOwnerGetsMinimalSessionForPersistedAmountAndPeerIp() throws Exception {
        User buyer = user();
        int orderId = order(buyer, 45_123L, OrderStatus.PENDING_PAYMENT, NOW.plusSeconds(1800));
        var response = mvc.perform(post("/api/orders/{orderId}/payment-session", orderId)
                        .header("Authorization", bearer(buyer))
                        .header("X-Forwarded-For", "203.0.113.77")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":999999,\"amount\":1,\"merchantReference\":\"forged\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.paymentUrl").exists())
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(jsonPath("$.data.reused").value(false))
                .andReturn();
        String body = response.getResponse().getContentAsString();
        JsonNode session = json.readTree(body).path("data");
        String url = session.path("paymentUrl").asText();
        assertThat(url).contains("vnp_Amount=4512300", "vnp_IpAddr=127.0.0.1");
        assertThat(body).doesNotContain("integration-test-only-secret", "paymentAttemptId", "merchantReference");
        assertThat(jdbcCount("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", orderId)).isEqualTo(1);
        assertThat(jdbcCount("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", orderId)).isZero();
        assertThat(jdbcCount("SELECT COUNT(*) FROM payment_events WHERE order_id=?", orderId)).isZero();
        assertThat(jdbcCount("SELECT COUNT(*) FROM course_claims WHERE order_id=?", orderId)).isZero();
        assertThat(jdbcCount("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?", orderId)).isZero();
        assertThat(jdbcText("SELECT status FROM orders WHERE id=?", orderId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void crossCustomerAndMissingOrdersAreNotDisclosed() throws Exception {
        User owner = user();
        User other = user();
        int orderId = order(owner, 10_000L, OrderStatus.PENDING_PAYMENT, NOW.plusSeconds(1800));
        mvc.perform(post("/api/orders/{orderId}/payment-session", orderId)
                        .header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/{orderId}/payment-session", Integer.MAX_VALUE)
                        .header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        assertThat(jdbcCount("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", orderId)).isZero();
    }

    @Test
    void expiredAndNonPendingOrdersCannotCreateSessions() throws Exception {
        User buyer = user();
        int expired = order(buyer, 10_000L, OrderStatus.PENDING_PAYMENT, NOW.minusNanos(1000));
        int completed = order(buyer, 10_000L, OrderStatus.COMPLETED, NOW.plusSeconds(1800));
        mvc.perform(post("/api/orders/{orderId}/payment-session", expired).header("Authorization", bearer(buyer)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/orders/{orderId}/payment-session", completed).header("Authorization", bearer(buyer)))
                .andExpect(status().isConflict());
        assertThat(jdbcCount("SELECT COUNT(*) FROM payment_attempts WHERE order_id IN (?,?)", expired, completed)).isZero();
    }

    @Test
    void courseOnlyEndpointRejectsProductOrderWithoutCreatingAttempt() throws Exception {
        User buyer = user();
        int productOrder = order(buyer, 10_000L, OrderStatus.PENDING_PAYMENT, NOW.plusSeconds(1800),
                OrderItemType.PRODUCT);
        mvc.perform(post("/api/orders/{orderId}/payment-session", productOrder)
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isConflict());
        assertThat(jdbcCount("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", productOrder)).isZero();
    }

    @Test
    void successfulSessionDoesNotChangeOrderPaymentOrFulfillmentState() throws Exception {
        User buyer = user();
        int orderId = order(buyer, 20_000L, OrderStatus.PENDING_PAYMENT, NOW.plusSeconds(1800));
        mvc.perform(post("/api/orders/{orderId}/payment-session", orderId).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk());
        assertThat(jdbcText("SELECT status FROM orders WHERE id=?", orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbcText("SELECT payment_status FROM orders WHERE id=?", orderId)).isEqualTo("CREATED");
        assertThat(jdbcCount("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", orderId)).isZero();
        assertThat(jdbcCount("SELECT COUNT(*) FROM payment_settlement_work_items w JOIN payment_events e "
                + "ON e.id=w.payment_event_id WHERE e.order_id=?", orderId)).isZero();
    }

    @Test
    void activeAttemptIsReusedAndDefinitiveFailureStartsOneNewAttempt() throws Exception {
        User buyer = user();
        int reusableOrder = order(buyer, 30_000L, OrderStatus.PENDING_PAYMENT, NOW.plusSeconds(1800));
        String auth = bearer(buyer);
        mvc.perform(post("/api/orders/{orderId}/payment-session", reusableOrder).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(false));
        mvc.perform(post("/api/orders/{orderId}/payment-session", reusableOrder).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(true));
        assertThat(attempts.findAllByOrderIdAndUserId(reusableOrder, buyer.getId())).hasSize(1);

        int failedOrder = order(buyer, 31_000L, OrderStatus.PENDING_PAYMENT, NOW.plusSeconds(1800));
        persistAttempt(failedOrder, buyer.getId(), PaymentStatus.FAILED);
        mvc.perform(post("/api/orders/{orderId}/payment-session", failedOrder).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(false));
        assertThat(attempts.findAllByOrderIdAndUserId(failedOrder, buyer.getId())).hasSize(2);
    }

    @Test
    void unknownAttemptRequiresReconciliationAndIsNotReplaced() throws Exception {
        User buyer = user();
        int orderId = order(buyer, 32_000L, OrderStatus.PENDING_PAYMENT, NOW.plusSeconds(1800));
        persistAttempt(orderId, buyer.getId(), PaymentStatus.UNKNOWN);
        mvc.perform(post("/api/orders/{orderId}/payment-session", orderId).header("Authorization", bearer(buyer)))
                .andExpect(status().isConflict());
        assertThat(attempts.findAllByOrderIdAndUserId(orderId, buyer.getId())).hasSize(1);
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private User user() {
        return users.saveAndFlush(User.builder().fullName("Payment session test")
                .email("payment-session-" + UUID.randomUUID() + "@example.test").passwordHash("test-hash")
                .role(Role.USER).isActive(true).build());
    }

    private int order(User buyer, long totalVnd, OrderStatus status, Instant expiresAt) {
        return order(buyer, totalVnd, status, expiresAt, OrderItemType.COURSE);
    }

    private int order(User buyer, long totalVnd, OrderStatus status, Instant expiresAt, OrderItemType itemType) {
        Instant created = expiresAt.isBefore(NOW) ? expiresAt.minusSeconds(1800) : NOW;
        created = created.truncatedTo(ChronoUnit.MICROS);
        Instant persistedExpiry = expiresAt.truncatedTo(ChronoUnit.MICROS);
        Order pending = Order.create(buyer.getId(), List.of(new OrderLineSnapshot(itemType,
                42, "Course session fixture", 1, totalVnd, true)), 0, Optional.empty(),
                Clock.fixed(created, ZoneOffset.UTC));
        Order domain = status == OrderStatus.PENDING_PAYMENT ? pending : Order.restore(new Order.Snapshot(
                buyer.getId(), pending.getItems(), pending.getVoucher(), pending.getCurrency(), status,
                pending.getPaymentStatus(), pending.getMerchandiseSubtotalVnd(), pending.getEligibleSubtotalVnd(),
                pending.getDiscountVnd(), pending.getShippingFeeVnd(), pending.getTotalVnd()), created, persistedExpiry);
        return new TransactionTemplate(transactions).execute(tx -> {
            OrderJpaEntity row = OrderJpaEntity.fromSnapshot(domain);
            entityManager.persist(row);
            entityManager.flush();
            return row.getId();
        });
    }

    private int persistAttempt(int orderId, int userId, PaymentStatus state) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var orderRow = orders.findByIdAndUserId(orderId, userId).orElseThrow();
            var order = new OrderPersistenceMapper().toDomain(orderRow);
            var domain = PaymentAttempt.create(orderId, order, "vnpay", "fixture-" + UUID.randomUUID(),
                    Clock.fixed(NOW.plusMillis(1), ZoneOffset.UTC));
            var mapper = new PaymentPersistenceMapper();
            var row = PaymentAttemptJpaEntity.newSnapshot(domain);
            entityManager.persist(row);
            entityManager.flush();
            if (state != PaymentStatus.CREATED) {
                var persisted = mapper.toDomain(row);
                var changed = new PaymentAttempt(row.getId(), persisted.orderId(), persisted.userId(),
                        persisted.provider(), persisted.merchantReference(), persisted.expectedAmountVnd(),
                        persisted.currency(), persisted.orderCreatedAt(), persisted.expiresAt(), persisted.createdAt(),
                        persisted.createdAt().plusMillis(1), state, null, null, PaymentAttempt.ReviewReason.NONE);
                row.applyDecision(changed);
                entityManager.flush();
            }
            return row.getId();
        });
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private int jdbcCount(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private String jdbcText(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }
}
