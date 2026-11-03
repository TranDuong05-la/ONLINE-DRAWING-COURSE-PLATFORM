package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.course.Course;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.EnrollmentRepository;
import com.artacademy.learning.claim.CourseClaimRepository;
import com.artacademy.learning.claim.CourseClaimService;
import com.artacademy.payment.vnpay.IpnClassification;
import com.artacademy.payment.vnpay.VnPayCallbackVerifier;
import com.artacademy.payment.vnpay.VnPayIpnReconciliationService;
import com.artacademy.payment.vnpay.VerifiedVnPayCallback;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.checkout.VoucherReservationPort;
import com.drawingcourse.backend.service.settlement.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production Course-only settlement composition with real B/C/D persistence participants. */
@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.payment.vnpay.enabled=true",
        "app.payment.vnpay.tmn-code=DEMOV210",
        "app.payment.vnpay.secret-key=course-settlement-test-secret",
        "app.payment.vnpay.return-url=https://merchant.example.test/return",
        "app.payment.vnpay.ipn-url=https://merchant.example.test/ipn",
        "app.payment.settlement.enabled=true",
        "app.payment.settlement.worker.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:course_only_settlement_real_c;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
})
@ActiveProfiles("test")
@Import(CourseOnlySettlementWithMemberCIntegrationH2Test.ClockConfiguration.class)
class CourseOnlySettlementWithMemberCIntegrationH2Test {
    private static final String SECRET = "course-settlement-test-secret";
    private static final java.util.concurrent.atomic.AtomicInteger TRANSACTION_IDS =
            new java.util.concurrent.atomic.AtomicInteger(99000000);
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired private ApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private CourseRepository courses;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentEventRepository events;
    @Autowired private PaymentSettlementRepository settlements;
    @Autowired private PaymentSettlementWorkItemRepository workItems;
    @Autowired private SettlementWorkClaimService claimsForWork;
    @Autowired private CourseClaimService courseClaims;
    @Autowired private CourseClaimRepository courseClaimRows;
    @Autowired private EnrollmentRepository enrollments;
    @Autowired private VoucherRepository vouchers;
    @Autowired private JpaVoucherReservationService voucherParticipant;
    @Autowired private VnPayCallbackVerifier verifier;
    @Autowired private VnPayIpnReconciliationService reconciliation;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SettlementCoordinator settlement;
    @Autowired private SettlementWorkerOrchestrator worker;
    @Autowired private TestClock clock;

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary TestClock courseSettlementClock() { return new TestClock(); }
    }

    static class TestClock extends Clock {
        private Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        void set(Instant value) { now = value.truncatedTo(ChronoUnit.MICROS); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Test
    void verifiedOutboxWorkConfirmsRealCourseClaimEnrollmentVoucherAndLedgerExactlyOnce() {
        assertNotNull(settlement);
        assertTrue(context.getBeansOfType(InventoryReservationConfirmationPort.class).isEmpty(),
                "Course-only settlement must not rely on an Inventory placeholder");
        assertEquals(1, context.getBeansOfType(SettlementWorkerOrchestrator.class).size(),
                "The worker bean is present only because this isolated test explicitly enables it");
        assertFalse(context.containsBean("settlementWorkerScheduler"),
                "Worker composition alone must not activate scheduled polling");
        Instant observed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(observed);
        Fixture fixture = fixture(observed, OrderItemType.COURSE, true, true);

        var reconciled = reconciliation.reconcile(signedSuccess(fixture));
        assertEquals(IpnClassification.VALID_ON_TIME_SUCCESS, reconciled.classification());
        PaymentSettlementWorkItemJpaEntity work = workItems.findByPaymentEventId(reconciled.eventId()).orElseThrow();
        assertEquals(PaymentSettlementWorkState.READY, work.getState());
        SettlementWorkClaimService.Claim lease = claimsForWork.claimNext("course-only-test").orElseThrow();

        SettlementResult result = settlement.executeClaimedWork(lease);

        assertEquals(SettlementResult.Outcome.SETTLED, result.outcome());
        assertEquals(OrderStatus.CONFIRMED, orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow().getStatus());
        Map<String, Object> claim = jdbc.queryForMap("SELECT learner_profile_id,enrollment_id,status,order_item_id FROM course_claims WHERE order_id=? AND course_id=?",
                fixture.orderId(), fixture.courseId());
        assertEquals("CONFIRMED", claim.get("status"));
        assertEquals(fixture.orderItemId(), ((Number) claim.get("order_item_id")).intValue());
        Integer profileId = ((Number) claim.get("learner_profile_id")).intValue();
        var enrollment = enrollments.findByLearnerProfileIdAndCourseId(profileId, fixture.courseId().longValue()).orElseThrow();
        assertTrue(enrollment.isActive());
        assertEquals(fixture.orderId(), enrollment.getSourceOrderId());
        assertEquals(fixture.orderItemId(), enrollment.getSourceOrderItemId());
        assertEquals(((Number) claim.get("enrollment_id")).longValue(), enrollment.getId());
        assertEquals(1, settlements.findByOrderId(fixture.orderId()).stream().count());
        assertEquals("REDEEMED", jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class, fixture.orderId()));
        assertEquals(PaymentSettlementWorkState.COMPLETED,
                workItems.findById(lease.id()).orElseThrow().getState());

        assertEquals(SettlementResult.Outcome.ALREADY_SETTLED, settlement.executeClaimedWork(lease).outcome());
        assertEquals(1, settlements.findByOrderId(fixture.orderId()).stream().count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=? AND source_order_item_id=?",
                Integer.class, fixture.orderId(), fixture.orderItemId()));
    }

    @Test
    void courseClaimFailureRollsBackOrderVoucherEnrollmentAndLedger() {
        Instant observed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(observed);
        Fixture fixture = fixture(observed, OrderItemType.COURSE, true, false);
        assertEquals(IpnClassification.VALID_ON_TIME_SUCCESS, reconciliation.reconcile(signedSuccess(fixture)).classification());
        SettlementWorkClaimService.Claim lease = claimsForWork.claimNext("course-only-missing-claim").orElseThrow();

        SettlementResult result = settlement.executeClaimedWork(lease);

        assertEquals(SettlementResult.Outcome.REQUIRES_REVIEW, result.outcome());
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow().getStatus());
        assertTrue(settlements.findByOrderId(fixture.orderId()).isEmpty());
        assertEquals("RESERVED", jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class, fixture.orderId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?", Integer.class, fixture.orderId()));
    }

    @Test
    void productOrderWithoutInventoryIsRejectedBeforeAnySettlementMutation() {
        Instant observed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(observed);
        Fixture fixture = fixture(observed, OrderItemType.PRODUCT, false, false);
        assertEquals(IpnClassification.VALID_ON_TIME_SUCCESS, reconciliation.reconcile(signedSuccess(fixture)).classification());
        SettlementWorkClaimService.Claim lease = claimsForWork.claimNext("course-only-product-reject").orElseThrow();

        SettlementResult result = settlement.executeClaimedWork(lease);

        assertEquals(SettlementResult.Outcome.REQUIRES_REVIEW, result.outcome());
        assertEquals(SettlementResult.Reason.FULFILLMENT_ADAPTER_UNAVAILABLE, result.reason());
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow().getStatus());
        assertTrue(settlements.findByOrderId(fixture.orderId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?", Integer.class, fixture.orderId()));
    }

    @Test
    void explicitDurableWorkerClaimsVerifiedCourseWorkAndActivatesRealEnrollment() {
        Instant observed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(observed);
        Fixture fixture = fixture(observed, OrderItemType.COURSE, false, true);
        var reconciled = reconciliation.reconcile(signedSuccess(fixture));
        assertEquals(IpnClassification.VALID_ON_TIME_SUCCESS, reconciled.classification());

        var summary = worker.runBatch("course-worker-integration", 1);

        assertEquals(1, summary.claimed());
        assertEquals(1, summary.completed());
        assertEquals(0, summary.manualReview());
        assertEquals(OrderStatus.CONFIRMED, orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow().getStatus());
        assertEquals(1, settlements.findByOrderId(fixture.orderId()).stream().count());
        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT status FROM course_claims WHERE order_id=?", String.class, fixture.orderId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=? AND source_order_item_id=?",
                Integer.class, fixture.orderId(), fixture.orderItemId()));
    }

    @Test
    void mixedOrderWithoutInventoryIsRejectedBeforeCourseOrVoucherMutation() {
        Instant observed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(observed);
        Fixture fixture = fixture(observed, OrderItemType.COURSE, true, true, true);
        assertEquals(IpnClassification.VALID_ON_TIME_SUCCESS, reconciliation.reconcile(signedSuccess(fixture)).classification());
        SettlementWorkClaimService.Claim lease = claimsForWork.claimNext("course-only-mixed-reject").orElseThrow();

        SettlementResult result = settlement.executeClaimedWork(lease);

        assertEquals(SettlementResult.Outcome.REQUIRES_REVIEW, result.outcome());
        assertEquals(SettlementResult.Reason.FULFILLMENT_ADAPTER_UNAVAILABLE, result.reason());
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow().getStatus());
        assertTrue(settlements.findByOrderId(fixture.orderId()).isEmpty());
        assertEquals("RESERVED", jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class, fixture.orderId()));
        assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM course_claims WHERE order_id=?", String.class, fixture.orderId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?", Integer.class, fixture.orderId()));
    }

    private Fixture fixture(Instant now, OrderItemType type, boolean withVoucher, boolean reserveClaim) {
        return fixture(now, type, withVoucher, reserveClaim, false);
    }

    private Fixture fixture(Instant now, OrderItemType type, boolean withVoucher, boolean reserveClaim, boolean mixed) {
        User fixtureUser = users.saveAndFlush(User.builder().fullName("Settlement integration")
                .email("settlement-" + UUID.randomUUID() + "@example.test").passwordHash("test-only")
                .role(Role.USER).isActive(true).build());
        return new TransactionTemplate(transactionManager).execute(status -> {
            User user = fixtureUser;
            Course course = null;
            Integer itemId;
            VoucherReservationPort.Quote quote = null;
            Instant created = now.minusSeconds(5);
            if (type == OrderItemType.COURSE) {
                String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                course = new Course();
                course.setCode("SET-" + suffix);
                course.setSlug("settlement-" + suffix);
                course.setTitle("Settlement Course");
                course.setPrice(20_000L);
                course.setLevel(CourseLevel.BASIC);
                course.setStatus(CourseStatus.PUBLISHED);
                course.setPublishedAt(LocalDateTime.ofInstant(now.minusSeconds(100), ZoneOffset.UTC));
                course = courses.saveAndFlush(course);
            }
            if (withVoucher) {
                String code = "SET-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
                Voucher definition = new Voucher(code, OrderVoucher.Kind.FIXED_VND, 1_000, OptionalLong.empty(), true,
                        now.minusSeconds(60), now.plusSeconds(3600), 0, OptionalLong.of(5), OptionalLong.of(2));
                vouchers.saveAndFlush(VoucherJpaEntity.fromDefinition(definition));
                quote = voucherParticipant.quoteAndLock(user.getId(), code, 20_000, now).orElseThrow();
            }
            int productId = 101;
            List<OrderLineSnapshot> lines = new ArrayList<>();
            lines.add(new OrderLineSnapshot(type, course == null ? productId : Math.toIntExact(course.getId()),
                    course == null ? "Product settlement" : course.getTitle(), 1, 20_000, type == OrderItemType.COURSE));
            if (mixed) lines.add(new OrderLineSnapshot(OrderItemType.PRODUCT, productId, "Mixed product", 1, 5_000, false));
            Order snapshot = Order.create(user.getId(), lines, 0,
                    quote == null ? Optional.empty() : Optional.of(quote.terms()), Clock.fixed(created, ZoneOffset.UTC));
            OrderJpaEntity order = OrderJpaEntity.fromSnapshot(snapshot);
            em.persist(order);
            em.flush();
            OrderItemJpaEntity persistedItem = order.getItems().getFirst();
            itemId = persistedItem.getId();
            if (quote != null) voucherParticipant.reserveForOrder(user.getId(), new OrderPersistenceMapper().toStored(order), snapshot, quote, created);
            if (reserveClaim && course != null) courseClaims.reserve(new CourseClaimContracts.ReservationCommand(
                    user.getId(), order.getId(), List.of(new CourseClaimContracts.ClaimLine(itemId, course.getId(),
                    CourseClaimContracts.ClaimSourceType.STANDALONE_COURSE, null)), created, snapshot.getExpiresAt()));

            PaymentAttempt pending = PaymentAttempt.create(order.getId(), snapshot, "vnpay", "course-settle-" + order.getId(),
                    Clock.fixed(created.plusSeconds(1), ZoneOffset.UTC));
            em.persist(PaymentAttemptJpaEntity.newSnapshot(pending));
            em.flush();
            return new Fixture(user.getId(), course == null ? null : Math.toIntExact(course.getId()), order.getId(),
                    itemId, pending.merchantReference(), pending.expectedAmountVnd(), created);
        });
    }

    private VerifiedVnPayCallback signedSuccess(Fixture f) {
        Instant paidAt = f.createdAt().plusSeconds(3);
        Map<String, String> values = new TreeMap<>();
        values.put("vnp_TmnCode", "DEMOV210");
        values.put("vnp_Amount", Long.toString(f.amountVnd() * 100));
        values.put("vnp_BankCode", "NCB");
        values.put("vnp_OrderInfo", "verified course settlement test");
        values.put("vnp_TransactionNo", Integer.toString(TRANSACTION_IDS.incrementAndGet()));
        values.put("vnp_ResponseCode", "00");
        values.put("vnp_TransactionStatus", "00");
        values.put("vnp_TxnRef", f.merchantReference());
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
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Fixture(Integer userId, Integer courseId, Integer orderId, Integer orderItemId,
                           String merchantReference, long amountVnd, Instant createdAt) { }
}
