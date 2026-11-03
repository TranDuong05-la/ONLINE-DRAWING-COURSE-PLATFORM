package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.course.Course;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.claim.CourseClaimRepository;
import com.artacademy.learning.claim.CourseClaimService;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.checkout.VoucherReservationPort;
import com.drawingcourse.backend.service.expiration.ExpirationResult;
import com.drawingcourse.backend.service.expiration.InventoryReservationReleasePort;
import com.drawingcourse.backend.service.expiration.OrderExpirationCoordinator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.order.expiration.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:course_only_expiration_real_c;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
})
@ActiveProfiles("test")
class CourseOnlyExpirationMemberCIntegrationH2Test {
    @Autowired private ApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private OrderRepository orders;
    @Autowired private VoucherRepository vouchers;
    @Autowired private JpaVoucherReservationService voucherService;
    @Autowired private CourseClaimService courseClaims;
    @Autowired private CourseClaimRepository claimRows;
    @Autowired private OrderExpirationCoordinator expiration;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void courseOnlyExpirationReleasesRealCourseClaimAndVoucherOnce() {
        assertNotNull(expiration);
        assertTrue(context.getBeansOfType(InventoryReservationReleasePort.class).isEmpty(),
                "Course-only expiry must not rely on an Inventory placeholder");
        assertFalse(context.containsBean("orderExpirationScheduler"),
                "Enabling the coordinator alone must not activate its scheduler");
        Fixture fixture = fixture(false, false);

        ExpirationResult first = expiration.expire(fixture.orderId());
        ExpirationResult duplicate = expiration.expire(fixture.orderId());

        assertEquals(ExpirationResult.Outcome.EXPIRED, first.outcome());
        assertEquals(ExpirationResult.Outcome.ALREADY_EXPIRED, duplicate.outcome());
        assertEquals(OrderStatus.EXPIRED, orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow().getStatus());
        assertEquals("RELEASED", jdbc.queryForObject("SELECT status FROM course_claims WHERE order_id=?", String.class, fixture.orderId()));
        assertEquals("RELEASED", jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class, fixture.orderId()));
        assertEquals(0L, jdbc.queryForObject("SELECT reserved_count FROM vouchers WHERE code=?", Long.class, fixture.voucherCode()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Integer.class, fixture.orderId()));
    }

    @Test
    void productAndMixedOrdersWithoutInventoryFailClosedBeforeAnyRelease() {
        Fixture product = fixture(true, false);
        Fixture mixed = fixture(false, true);

        ExpirationResult productResult = expiration.expire(product.orderId());
        ExpirationResult mixedResult = expiration.expire(mixed.orderId());

        assertEquals(ExpirationResult.Outcome.REQUIRES_REVIEW, productResult.outcome());
        assertEquals(ExpirationResult.Reason.FULFILLMENT_ADAPTER_UNAVAILABLE, productResult.reason());
        assertEquals(ExpirationResult.Outcome.REQUIRES_REVIEW, mixedResult.outcome());
        assertEquals(ExpirationResult.Reason.FULFILLMENT_ADAPTER_UNAVAILABLE, mixedResult.reason());
        for (Fixture fixture : List.of(product, mixed)) {
            assertEquals(OrderStatus.PENDING_PAYMENT, orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow().getStatus());
            assertEquals("RESERVED", jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class, fixture.orderId()));
            assertEquals(1L, jdbc.queryForObject("SELECT reserved_count FROM vouchers WHERE code=?", Long.class, fixture.voucherCode()));
        }
        assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM course_claims WHERE order_id=?", String.class, mixed.orderId()));
    }

    private Fixture fixture(boolean productOnly, boolean mixed) {
        User user = users.saveAndFlush(User.builder().fullName("Expiry integration")
                .email("expiry-" + UUID.randomUUID() + "@example.test").passwordHash("test-only")
                .role(Role.USER).isActive(true).build());
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant created = now.minusSeconds(31 * 60L);
        String code = "EXP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return new TransactionTemplate(transactions).execute(status -> {
            Course course = null;
            if (!productOnly) {
                String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                course = new Course();
                course.setCode("EX-" + suffix);
                course.setSlug("expiry-" + suffix);
                course.setTitle("Expiry course");
                course.setPrice(20_000L);
                course.setLevel(CourseLevel.BASIC);
                course.setStatus(CourseStatus.PUBLISHED);
                course.setPublishedAt(LocalDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC));
                course = courses.saveAndFlush(course);
            }
            Voucher voucher = new Voucher(code, OrderVoucher.Kind.FIXED_VND, 1_000, OptionalLong.empty(), true,
                    created.minusSeconds(60), now.plusSeconds(3600), 0, OptionalLong.of(10), OptionalLong.of(3));
            vouchers.saveAndFlush(VoucherJpaEntity.fromDefinition(voucher));
            var quote = voucherService.quoteAndLock(user.getId(), code, 20_000, created).orElseThrow();
            List<OrderLineSnapshot> lines = new ArrayList<>();
            if (!productOnly) lines.add(new OrderLineSnapshot(OrderItemType.COURSE,
                    Math.toIntExact(course.getId()), course.getTitle(), 1, 20_000, true));
            if (productOnly || mixed) lines.add(new OrderLineSnapshot(OrderItemType.PRODUCT, 771,
                    "Expiry product", 1, productOnly ? 20_000 : 5_000, productOnly));
            Order snapshot = Order.create(user.getId(), lines, 0, Optional.of(quote.terms()), Clock.fixed(created, ZoneOffset.UTC));
            OrderJpaEntity row = OrderJpaEntity.fromSnapshot(snapshot);
            em.persist(row);
            em.flush();
            var stored = new OrderPersistenceMapper().toStored(row);
            voucherService.reserveForOrder(user.getId(), stored, snapshot, quote, created);
            if (course != null) {
                OrderItemJpaEntity courseItem = row.getItems().stream()
                        .filter(i -> i.getItemType() == OrderItemType.COURSE).findFirst().orElseThrow();
                courseClaims.reserve(new CourseClaimContracts.ReservationCommand(user.getId(), row.getId(), List.of(
                        new CourseClaimContracts.ClaimLine(courseItem.getId(), course.getId(),
                                CourseClaimContracts.ClaimSourceType.STANDALONE_COURSE, null)), created, snapshot.getExpiresAt()));
            }
            return new Fixture(user.getId(), row.getId(), code);
        });
    }

    private record Fixture(Integer userId, Integer orderId, String voucherCode) { }
}
