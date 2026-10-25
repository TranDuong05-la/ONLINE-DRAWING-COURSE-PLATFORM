package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ArtAcademyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderHistoryApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CustomUserDetailsService userDetails;
    @Autowired JwtService jwt;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager tx;
    @Autowired OrderRepository orders;

    @BeforeEach void resetStatistics() { emf.unwrap(SessionFactory.class).getStatistics().setStatisticsEnabled(true); }

    @Test void unauthenticatedListAndDetailAreRejected() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders/1")).andExpect(status().isUnauthorized());
    }

    @Test void listIsOwnerScopedSortedAndPagedWithScalarItemCounts() throws Exception {
        User owner = createUser();
        User other = createUser();
        Instant tied = Instant.parse("2026-04-10T12:00:00Z");
        Integer first = createOrder(owner, tied, false, false);
        Integer second = createOrder(owner, tied, true, true);
        createOrder(owner, tied.minusSeconds(1), false, false);
        createOrder(other, tied.plusSeconds(60), false, false);

        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mvc.perform(get("/api/orders").header("Authorization", bearer(owner)).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].orderId").value(Math.max(first, second)))
                .andExpect(jsonPath("$.data.content[1].orderId").value(Math.min(first, second)))
                .andExpect(jsonPath("$.data.content[0].itemCount").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.first").value(true))
                .andExpect(jsonPath("$.data.last").value(false))
                .andExpect(jsonPath("$.data.empty").value(false));
        assertThat(statistics.getPrepareStatementCount()).as("JWT lookup, scalar page and count only; never per-order item loads")
                .isLessThanOrEqualTo(4);
        mvc.perform(get("/api/orders").header("Authorization", bearer(owner)).param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.number").value(1)).andExpect(jsonPath("$.data.last").value(true));
    }

    @Test void emptyHistoryAndPaginationBoundsAreHandled() throws Exception {
        User user = createUser();
        mvc.perform(get("/api/orders").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0)).andExpect(jsonPath("$.data.empty").value(true));
        for (String[] params : List.of(new String[]{"page", "-1"}, new String[]{"size", "0"},
                new String[]{"size", "101"}, new String[]{"size", "not-number"})) {
            mvc.perform(get("/api/orders").header("Authorization", bearer(user)).param(params[0], params[1]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/orders").header("Authorization", bearer(user)).param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(100));
    }

    @Test void detailReturnsOnlyImmutablePersistedSnapshotsAndOptionalAddress() throws Exception {
        User owner = createUser();
        Instant created = Instant.parse("2026-05-01T01:02:03.123456Z");
        Integer withAddress = createOrder(owner, created, true, true);
        Integer withoutAddress = createOrder(owner, created.plusSeconds(1), false, false);
        var before = orders.findByIdAndUserId(withAddress, owner.getId()).orElseThrow();
        long totalBefore = before.getTotalVnd();

        mvc.perform(get("/api/orders/{id}", withAddress).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(withAddress))
                .andExpect(jsonPath("$.data.paymentExpiresAt").value("2026-05-01T01:32:03.123456Z"))
                .andExpect(jsonPath("$.data.merchandiseSubtotalVnd").value(200000))
                .andExpect(jsonPath("$.data.eligibleSubtotalVnd").value(200000))
                .andExpect(jsonPath("$.data.discountVnd").value(10000))
                .andExpect(jsonPath("$.data.shippingFeeVnd").value(2500))
                .andExpect(jsonPath("$.data.totalVnd").value(192500))
                .andExpect(jsonPath("$.data.voucher.code").value("HISTORY-10K"))
                .andExpect(jsonPath("$.data.items[0].itemId").value(31))
                .andExpect(jsonPath("$.data.items[0].nameSnapshot").value("Historical brush set"))
                .andExpect(jsonPath("$.data.items[0].unitPriceVnd").value(100000))
                .andExpect(jsonPath("$.data.items[0].grossAmountVnd").value(200000))
                .andExpect(jsonPath("$.data.items[0].allocatedDiscountVnd").value(10000))
                .andExpect(jsonPath("$.data.items[0].netAmountVnd").value(190000))
                .andExpect(jsonPath("$.data.shippingAddress.recipient").value("Historical recipient"))
                .andExpect(jsonPath("$.data.userId").doesNotExist());
        mvc.perform(get("/api/orders/{id}", withoutAddress).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shippingAddress").doesNotExist());
        var after = orders.findByIdAndUserId(withAddress, owner.getId()).orElseThrow();
        assertThat(after.getTotalVnd()).isEqualTo(totalBefore);
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test void detailItemsFollowPersistedLinePosition() throws Exception {
        User owner = createUser();
        Instant created = Instant.parse("2026-08-01T00:00:00Z");
        var snapshot = Order.create(owner.getId(), List.of(
                new OrderLineSnapshot(OrderItemType.PRODUCT, 51, "First line", 1, 20_000, true),
                new OrderLineSnapshot(OrderItemType.COURSE, 61, "Second line", 1, 30_000, true)),
                0, Optional.empty(), Clock.fixed(created, ZoneOffset.UTC));
        Integer id = new TransactionTemplate(tx).execute(status -> {
            var row = OrderJpaEntity.fromSnapshot(snapshot);
            em.persist(row);
            em.flush();
            return row.getId();
        });
        mvc.perform(get("/api/orders/{id}", id).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].linePosition").value(0))
                .andExpect(jsonPath("$.data.items[0].itemId").value(51))
                .andExpect(jsonPath("$.data.items[1].linePosition").value(1))
                .andExpect(jsonPath("$.data.items[1].itemId").value(61));
    }
    @Test void nullablePersistedDeadlineIsReturnedAndInvalidIdsAreBadRequest() throws Exception {
        User owner = createUser();
        Integer id = createOrderWithNullableDeadline(owner, Instant.parse("2026-06-01T00:00:00Z"));
        mvc.perform(get("/api/orders/{id}", id).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentExpiresAt").value((Object) null));
        mvc.perform(get("/api/orders/0").header("Authorization", bearer(owner))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders/-2").header("Authorization", bearer(owner))).andExpect(status().isBadRequest());
    }

    @Test void foreignAndMissingOrdersBothReturnSameNotFound() throws Exception {
        User owner = createUser();
        User foreign = createUser();
        Integer id = createOrder(foreign, Instant.parse("2026-07-01T00:00:00Z"), false, false);
        var foreignResult = mvc.perform(get("/api/orders/{id}", id).header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var missingResult = mvc.perform(get("/api/orders/{id}", id + 1000000).header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(foreignResult).contains("Order not found");
        assertThat(missingResult).contains("Order not found");
    }

    private Integer createOrderWithNullableDeadline(User user, Instant createdAt) {
        var base = Order.create(user.getId(), List.of(new OrderLineSnapshot(OrderItemType.COURSE, 44,
                "Course without deadline", 1, 50_000, true)), 0, Optional.empty(), Clock.fixed(createdAt, ZoneOffset.UTC));
        var restored = Order.restore(new Order.Snapshot(base.getUserId(), base.getItems(), base.getVoucher(),
                base.getCurrency(), base.getStatus(), base.getPaymentStatus(), base.getMerchandiseSubtotalVnd(),
                base.getEligibleSubtotalVnd(), base.getDiscountVnd(), base.getShippingFeeVnd(), base.getTotalVnd()),
                createdAt, null);
        return new TransactionTemplate(tx).execute(status -> {
            var row = OrderJpaEntity.fromSnapshot(restored);
            em.persist(row);
            em.flush();
            return row.getId();
        });
    }
    private User createUser() {
        return users.saveAndFlush(User.builder().fullName("Order History Test").email("history-" + UUID.randomUUID() + "@example.test")
                .passwordHash("test-hash").role(Role.USER).isActive(true).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private Integer createOrder(User user, Instant createdAt, boolean voucher, boolean address) {
        var lines = List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 31, "Historical brush set", 2, 100_000, true));
        var orderVoucher = voucher ? Optional.of(new OrderVoucher("HISTORY-10K", OrderVoucher.Kind.FIXED_VND, 10_000, 10_000)) : Optional.<OrderVoucher>empty();
        var snapshot = Order.create(user.getId(), lines, 2_500, orderVoucher, Clock.fixed(createdAt, ZoneOffset.UTC));
        return new TransactionTemplate(tx).execute(status -> {
            var row = OrderJpaEntity.fromSnapshot(snapshot);
            em.persist(row);
            em.flush();
            if (address) em.persist(new OrderShippingAddressJpaEntity(row, new CheckoutRequest.Address(
                    "Historical recipient", "0900000000", "Old street", "Old locality", "700000", "VN")));
            em.flush();
            return row.getId();
        });
    }
}
