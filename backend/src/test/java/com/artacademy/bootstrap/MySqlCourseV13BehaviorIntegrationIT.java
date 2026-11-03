package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.course.Course;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.entity.UserProfile;
import com.artacademy.enums.Role;
import com.artacademy.enums.ProfileType;
import com.artacademy.learning.EnrollmentRepository;
import com.artacademy.learning.claim.CourseClaimService;
import com.artacademy.repository.UserRepository;
import com.artacademy.repository.UserProfileRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.expiration.ExpirationResult;
import com.drawingcourse.backend.service.expiration.OrderExpirationCoordinator;
import com.drawingcourse.backend.service.settlement.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Opt-in, persistent-fixture integration checks for the combined V1-V13 Course-only path.
 * The target guard runs before Spring creates a datasource. Unique fixture keys are recorded before insert,
 * and cleanup discovers committed Orders by their dedicated test User. An abrupt JVM/process termination still
 * bypasses @AfterEach; the next run refuses to proceed while reserved fixture namespaces contain leftovers.
 */
@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "mysql.course.v13.behavior.test-opt-in=true",
        "app.cart.course-only.api-enabled=true",
        "app.checkout.course-only.enabled=true",
        "app.checkout.course-only.http-enabled=true",
        "app.checkout.course-only.catalog-adapter.enabled=true",
        "app.payment.vnpay.enabled=true",
        "app.payment.vnpay.session-api.enabled=true",
        "app.payment.vnpay.ipn.enabled=true",
        "app.payment.vnpay.tmn-code=DEMOV210",
        "app.payment.vnpay.secret-key=course-v13-test-only-secret",
        "app.payment.vnpay.return-url=https://merchant.example.test/return",
        "app.payment.vnpay.ipn-url=https://merchant.example.test/ipn",
        "app.payment.settlement.enabled=true",
        "app.payment.settlement.worker.enabled=true",
        "app.payment.settlement.worker.scheduler.enabled=false",
        "app.order.expiration.enabled=true",
        "app.order.expiration.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mysql-course-v13-it"})
@ContextConfiguration(initializers = CourseV13MySqlTargetGuard.class)
@Import(MySqlCourseV13BehaviorIntegrationIT.ClockConfiguration.class)
class MySqlCourseV13BehaviorIntegrationIT {
    private static final String SECRET = "course-v13-test-only-secret";
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final AtomicInteger PROVIDER_TRANSACTION = new AtomicInteger(730_000_000);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private org.springframework.context.ApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private UserProfileRepository userProfiles;
    @Autowired private CourseRepository courses;
    @Autowired private CartRepository carts;
    @Autowired private VoucherRepository vouchers;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentSettlementRepository settlements;
    @Autowired private PaymentSettlementWorkItemRepository workItems;
    @Autowired private PaymentSettlementWorkClaimRepository workClaimRepository;
    @Autowired private SettlementWorkClaimService workClaims;
    @Autowired private SettlementWorkerOrchestrator worker;
    @Autowired private SettlementCoordinator settlement;
    @Autowired private CourseClaimService courseClaims;
    @Autowired private EnrollmentRepository enrollments;
    @Autowired private JpaVoucherReservationService voucherParticipant;
    @Autowired private OrderExpirationCoordinator expiration;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private JwtService jwt;
    @Autowired private TestClock clock;

    // Record unique natural keys before persistence so cleanup can recover IDs even when
    // a helper fails after a transaction committed but before returning generated IDs.
    private final List<String> fixtureUserEmails = new CopyOnWriteArrayList<>();
    private final List<String> fixtureCourseCodes = new CopyOnWriteArrayList<>();
    private final List<String> fixtureVoucherCodes = new CopyOnWriteArrayList<>();
    private final List<Integer> fixtureUserIds = new CopyOnWriteArrayList<>();
    private final List<Integer> fixtureOrderIds = new CopyOnWriteArrayList<>();
    private final List<Long> fixtureCourseIds = new CopyOnWriteArrayList<>();
    private final List<Integer> fixtureVoucherIds = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary TestClock mysqlCourseV13Clock() { return new TestClock(); }
    }

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MICROS));
        void set(Instant value) { now.set(value.truncatedTo(ChronoUnit.MICROS)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @BeforeEach
    void refuseToRunWhenPriorProcessLeftFixtures() {
        int usersLeft = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email LIKE 'course-v13-%@example.test'",
                Integer.class);
        int ordersLeft = jdbc.queryForObject("SELECT COUNT(*) FROM orders o JOIN users u ON u.id=o.user_id "
                + "WHERE u.email LIKE 'course-v13-%@example.test'", Integer.class);
        int courseRowsLeft = jdbc.queryForObject("SELECT COUNT(*) FROM courses WHERE code LIKE 'V13-%' "
                + "OR code LIKE 'V13D-%'", Integer.class);
        int vouchersLeft = jdbc.queryForObject("SELECT COUNT(*) FROM vouchers WHERE code LIKE 'V13-%'", Integer.class);
        int notificationsLeft = jdbc.queryForObject("SELECT COUNT(*) FROM learning_notifications n "
                + "JOIN users u ON u.id=n.user_id WHERE u.email LIKE 'course-v13-%@example.test'", Integer.class);
        assertThat(List.of(usersLeft, ordersLeft, courseRowsLeft, vouchersLeft, notificationsLeft))
                .as("previous V13 test process may have terminated before @AfterEach cleanup; inspect only these "
                        + "reserved fixture namespaces before retrying")
                .containsOnly(0);
    }

    @AfterEach
    void deleteOnlyTrackedFixtures() {
        Set<Integer> ownedUsers = new TreeSet<>(fixtureUserIds);
        Set<Integer> ownedOrders = new TreeSet<>(fixtureOrderIds);
        Set<Integer> ownedVouchers = new TreeSet<>(fixtureVoucherIds);
        Set<Long> ownedCourses = new TreeSet<>(fixtureCourseIds);

        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                for (String email : fixtureUserEmails) {
                    ownedUsers.addAll(jdbc.query("SELECT id FROM users WHERE email=?",
                            (rs, row) -> rs.getInt(1), email));
                }
                // Lock each tracked parent before deleting children. An async after-commit notification
                // either commits first and is deleted below, or waits and then fails its FK after user deletion.
                for (Integer userId : ownedUsers) {
                    jdbc.query("SELECT id FROM users WHERE id=? FOR UPDATE", (rs, row) -> rs.getInt(1), userId);
                }
                for (Integer userId : ownedUsers) {
                    ownedOrders.addAll(jdbc.query("SELECT id FROM orders WHERE user_id=?",
                            (rs, row) -> rs.getInt(1), userId));
                }
                for (String code : fixtureVoucherCodes) {
                    ownedVouchers.addAll(jdbc.query("SELECT id FROM vouchers WHERE code=?",
                            (rs, row) -> rs.getInt(1), code));
                }
                for (String code : fixtureCourseCodes) {
                    ownedCourses.addAll(jdbc.query("SELECT id FROM courses WHERE code=?",
                            (rs, row) -> rs.getLong(1), code));
                }

                for (Integer orderId : ownedOrders) {
                    jdbc.update("DELETE FROM payment_settlement_work_actions WHERE work_item_id IN "
                            + "(SELECT id FROM payment_settlement_work_items WHERE payment_event_id IN "
                            + "(SELECT id FROM payment_events WHERE order_id=?))", orderId);
                    jdbc.update("DELETE FROM payment_settlement_work_items WHERE payment_event_id IN "
                            + "(SELECT id FROM payment_events WHERE order_id=?)", orderId);
                    jdbc.update("DELETE FROM payment_reconciliation_cases WHERE order_id=?", orderId);
                    jdbc.update("DELETE FROM payment_event_conflicts WHERE order_id=?", orderId);
                    jdbc.update("DELETE FROM payment_settlements WHERE order_id=?", orderId);
                    jdbc.update("DELETE FROM payment_events WHERE order_id=?", orderId);
                    jdbc.update("DELETE FROM payment_attempts WHERE order_id=?", orderId);
                    jdbc.update("DELETE FROM course_claims WHERE order_id=?", orderId);
                    jdbc.update("DELETE FROM user_course_enrollments WHERE source_order_id=?", orderId);
                    jdbc.update("DELETE FROM voucher_claims WHERE order_id=?", orderId);
                    jdbc.update("DELETE FROM checkout_requests WHERE order_id=?", orderId);
                    jdbc.update("DELETE FROM order_items WHERE order_id=?", orderId);
                    jdbc.update("DELETE FROM orders WHERE id=?", orderId);
                }
                for (Integer userId : ownedUsers) {
                    jdbc.update("DELETE FROM learning_notifications WHERE user_id=?", userId);
                    jdbc.update("DELETE FROM user_course_enrollments WHERE user_id=?", userId);
                    jdbc.update("DELETE FROM course_claims WHERE user_id=?", userId);
                    jdbc.update("DELETE FROM user_addresses WHERE user_id=?", userId);
                    jdbc.update("DELETE FROM assignment_submissions WHERE learner_profile_id IN "
                            + "(SELECT id FROM user_profiles WHERE user_id=?)", userId);
                    jdbc.update("DELETE FROM user_lesson_progress WHERE learner_profile_id IN "
                            + "(SELECT id FROM user_profiles WHERE user_id=?)", userId);
                    jdbc.update("DELETE FROM certificates WHERE learner_profile_id IN "
                            + "(SELECT id FROM user_profiles WHERE user_id=?)", userId);
                    jdbc.update("DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE user_id=?)", userId);
                    jdbc.update("DELETE FROM carts WHERE user_id=?", userId);
                    jdbc.update("DELETE FROM checkout_requests WHERE user_id=?", userId);
                    jdbc.update("DELETE FROM user_profiles WHERE user_id=?", userId);
                }
                for (Integer voucherId : ownedVouchers) {
                    jdbc.update("DELETE FROM voucher_user_usage WHERE voucher_id=?", voucherId);
                    jdbc.update("DELETE FROM vouchers WHERE id=?", voucherId);
                }
                for (Long courseId : ownedCourses) jdbc.update("DELETE FROM courses WHERE id=?", courseId);
                for (Integer userId : ownedUsers) jdbc.update("DELETE FROM users WHERE id=?", userId);
                assertNoResidualFixtureRows(ownedUsers, ownedOrders, ownedCourses, ownedVouchers);
            });
        } catch (RuntimeException failure) {
            String residual = safeResidualReport(ownedUsers, ownedOrders, ownedCourses, ownedVouchers);
            throw new IllegalStateException("V13 MySQL fixture cleanup failed. " + residual, failure);
        }
        fixtureOrderIds.clear(); fixtureUserIds.clear(); fixtureCourseIds.clear(); fixtureVoucherIds.clear();
        fixtureUserEmails.clear(); fixtureCourseCodes.clear(); fixtureVoucherCodes.clear();
    }
    private void assertNoResidualFixtureRows(Set<Integer> usersOwned, Set<Integer> ordersOwned,
                                             Set<Long> coursesOwned, Set<Integer> vouchersOwned) {
        Map<String, Integer> residual = new LinkedHashMap<>();
        residual.put("payment_settlement_work_actions", countWorkChildren(
                "payment_settlement_work_actions", ordersOwned));
        residual.put("payment_settlement_work_items", countWorkChildren(
                "payment_settlement_work_items", ordersOwned));
        for (String table : List.of("payment_reconciliation_cases", "payment_event_conflicts",
                "payment_settlements", "payment_events", "payment_attempts", "course_claims")) {
            residual.put(table, countByOrder(table, ordersOwned));
        }
        residual.put("user_course_enrollments", countOwned(
                "SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id IN (:ids)", ordersOwned));
        residual.put("user_course_enrollments_by_user", countByOwner("user_course_enrollments", usersOwned));
        residual.put("learning_notifications", countByOwner("learning_notifications", usersOwned));
        residual.put("course_claims_by_user", countByOwner("course_claims", usersOwned));
        residual.put("user_addresses", countByOwner("user_addresses", usersOwned));
        residual.put("user_lesson_progress_by_fixture_profile", countOwned(
                "SELECT COUNT(*) FROM user_lesson_progress WHERE learner_profile_id IN "
                        + "(SELECT id FROM user_profiles WHERE user_id IN (:ids))", usersOwned));
        residual.put("assignment_submissions_by_fixture_profile", countOwned(
                "SELECT COUNT(*) FROM assignment_submissions WHERE learner_profile_id IN "
                        + "(SELECT id FROM user_profiles WHERE user_id IN (:ids))", usersOwned));
        residual.put("assignment_submissions_reviewed_by_user", countOwned(
                "SELECT COUNT(*) FROM assignment_submissions WHERE reviewed_by_user_id IN (:ids)", usersOwned));
        residual.put("certificates_by_fixture_profile", countOwned(
                "SELECT COUNT(*) FROM certificates WHERE learner_profile_id IN "
                        + "(SELECT id FROM user_profiles WHERE user_id IN (:ids))", usersOwned));
        residual.put("voucher_claims", countByOrder("voucher_claims", ordersOwned));
        residual.put("checkout_requests", countByOrder("checkout_requests", ordersOwned));
        residual.put("order_items", countByOrder("order_items", ordersOwned));
        residual.put("orders", countOwned("SELECT COUNT(*) FROM orders WHERE id IN (:ids)", ordersOwned));
        residual.put("cart_items", countOwned("SELECT COUNT(*) FROM cart_items ci JOIN carts c ON c.id=ci.cart_id "
                + "WHERE c.user_id IN (:ids)", usersOwned));
        residual.put("carts", countByOwner("carts", usersOwned));
        residual.put("user_profiles", countByOwner("user_profiles", usersOwned));
        residual.put("users", countOwned("SELECT COUNT(*) FROM users WHERE id IN (:ids)", usersOwned));
        residual.put("voucher_user_usage", countOwned("SELECT COUNT(*) FROM voucher_user_usage WHERE voucher_id IN (:ids)", vouchersOwned));
        residual.put("vouchers", countOwned("SELECT COUNT(*) FROM vouchers WHERE id IN (:ids)", vouchersOwned));
        residual.put("courses", countOwned("SELECT COUNT(*) FROM courses WHERE id IN (:ids)", coursesOwned));
        residual.entrySet().removeIf(entry -> entry.getValue() == 0);
        if (!residual.isEmpty()) throw new IllegalStateException("Residual V13 fixture rows: " + residual);
    }

    private int countOwned(String sql, Collection<? extends Number> ids) {
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.queryForObject(sql.replace("(:ids)", "(" + placeholders + ")"), Integer.class, ids.toArray());
    }

    private int countByOrder(String table, Collection<Integer> orderIds) {
        return countOwned("SELECT COUNT(*) FROM " + table + " WHERE order_id IN (:ids)", orderIds);
    }

    private int countByOwner(String table, Collection<Integer> userIds) {
        return countOwned("SELECT COUNT(*) FROM " + table + " WHERE user_id IN (:ids)", userIds);
    }

    private int countWorkChildren(String table, Collection<Integer> orderIds) {
        if (orderIds.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
        String eventLink = "SELECT e.id FROM payment_events e WHERE e.order_id IN (" + placeholders + ")";
        String sql = table.equals("payment_settlement_work_items")
                ? "SELECT COUNT(*) FROM payment_settlement_work_items w WHERE w.payment_event_id IN (" + eventLink + ")"
                : "SELECT COUNT(*) FROM payment_settlement_work_actions a JOIN payment_settlement_work_items w "
                    + "ON w.id=a.work_item_id WHERE w.payment_event_id IN (" + eventLink + ")";
        return jdbc.queryForObject(sql, Integer.class, orderIds.toArray());
    }

    private String safeResidualReport(Set<Integer> userIds, Set<Integer> orderIds,
                                      Set<Long> courseIds, Set<Integer> voucherIds) {
        try {
            Set<Integer> users = new TreeSet<>(userIds);
            for (String email : fixtureUserEmails) {
                users.addAll(jdbc.query("SELECT id FROM users WHERE email=?", (rs, row) -> rs.getInt(1), email));
            }
            Set<Integer> orders = new TreeSet<>(orderIds);
            for (Integer userId : users) {
                orders.addAll(jdbc.query("SELECT id FROM orders WHERE user_id=?", (rs, row) -> rs.getInt(1), userId));
            }
            Set<Long> courses = new TreeSet<>(courseIds);
            for (String code : fixtureCourseCodes) {
                courses.addAll(jdbc.query("SELECT id FROM courses WHERE code=?", (rs, row) -> rs.getLong(1), code));
            }
            Set<Integer> vouchers = new TreeSet<>(voucherIds);
            for (String code : fixtureVoucherCodes) {
                vouchers.addAll(jdbc.query("SELECT id FROM vouchers WHERE code=?", (rs, row) -> rs.getInt(1), code));
            }
            assertNoResidualFixtureRows(users, orders, courses, vouchers);
            return "no residual fixture rows found";
        } catch (RuntimeException reportFailure) {
            return "residual fixture report=" + reportFailure.getMessage();
        }
    }

    @Test
    void checkoutConcurrentSessionsVerifiedDuplicateIpnAndRealCourseSettlementAreAtomic() throws Exception {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        Purchase fixture = checkout(true);
        String authorization = bearer(fixture.user());

        List<MvcResult> sessions = concurrentSessionInitiations(fixture.orderId(), authorization);
        assertThat(sessions).hasSize(2).allSatisfy(result -> assertThat(result.getResponse().getStatus())
                .as("resolved exception: %s; response body: %s", result.getResolvedException(),
                        result.getResponse().getContentAsString())
                .isEqualTo(200));
        assertThat(attempts.findAllByOrderIdAndUserId(fixture.orderId(), fixture.user().getId())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", Integer.class,
                fixture.orderId())).isEqualTo(1);

        PaymentAttemptJpaEntity attempt = attempts.findAllByOrderIdAndUserId(fixture.orderId(), fixture.user().getId())
                .getFirst();
        CallbackTimes callbackTimes = callbackTimesFor(attempt);
        Instant paidAt = callbackTimes.providerPaidAt();
        var callback = signedCallback(attempt.getMerchantReference(), attempt.getExpectedAmountVnd(), paidAt);
        MvcResult firstIpn = postIpn(callback);
        MvcResult replayIpn = postIpn(callback);
        assertThat(firstIpn.getResponse().getStatus()).isEqualTo(200);
        assertThat(replayIpn.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(firstIpn.getResponse().getContentAsString()).path("RspCode").asText())
                .isEqualTo("00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE order_id=?", Integer.class,
                fixture.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlement_work_items w "
                + "JOIN payment_events e ON e.id=w.payment_event_id WHERE e.order_id=?", Integer.class,
                fixture.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT item_type FROM order_items WHERE id=?", String.class,
                fixture.orderItemId())).isEqualTo("COURSE");
        assertThat(jdbc.queryForObject("SELECT order_item_id FROM course_claims WHERE order_id=?", Integer.class,
                fixture.orderId())).isEqualTo(fixture.orderItemId());
        assertThat(jdbc.queryForObject("SELECT status FROM course_claims WHERE order_id=?", String.class,
                fixture.orderId())).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class,
                fixture.orderId())).isEqualTo("RESERVED");

        Integer eventId = jdbc.queryForObject("SELECT id FROM payment_events WHERE order_id=?", Integer.class,
                fixture.orderId());
        assertOnlyClaimableWorkIs(eventId);
        var batch = worker.runBatch("course-v13-mysql-it", 1);

        assertThat(batch.claimed()).isEqualTo(1);
        assertThat(batch.completed()).isEqualTo(1);
        assertThat(orders.findByIdAndUserId(fixture.orderId(), fixture.user().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("SELECT status FROM course_claims WHERE order_id=?", String.class,
                fixture.orderId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments "
                + "WHERE source_order_id=? AND source_order_item_id=? AND status='ACTIVE'", Integer.class,
                fixture.orderId(), fixture.orderItemId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class,
                fixture.orderId())).isEqualTo("REDEEMED");
        assertThat(settlements.findByOrderId(fixture.orderId())).isPresent();
        assertThat(jdbc.queryForObject("SELECT state FROM payment_settlement_work_items WHERE payment_event_id=?",
                String.class, eventId)).isEqualTo("COMPLETED");
        assertThat(applicationContext.getBeansOfType(InventoryReservationConfirmationPort.class)).isEmpty();
        assertThat(applicationContext.containsBean("settlementWorkerScheduler")).isFalse();
        assertThat(applicationContext.containsBean("orderExpirationScheduler")).isFalse();
    }

    @Test
    void realCourseExpirationReleasesClaimAndVoucherIdempotently() throws Exception {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        Purchase fixture = checkout(true);
        clock.set(orders.findByIdAndUserId(fixture.orderId(), fixture.user().getId()).orElseThrow()
                .getPaymentExpiresAt());

        ExpirationResult first = expiration.expire(fixture.orderId());
        ExpirationResult replay = expiration.expire(fixture.orderId());

        assertThat(first.outcome()).isEqualTo(ExpirationResult.Outcome.EXPIRED);
        assertThat(replay.outcome()).isEqualTo(ExpirationResult.Outcome.ALREADY_EXPIRED);
        assertThat(jdbc.queryForObject("SELECT status FROM course_claims WHERE order_id=?", String.class,
                fixture.orderId())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class,
                fixture.orderId())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT reserved_count FROM vouchers WHERE id=?", Long.class,
                fixture.voucherId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Integer.class,
                fixture.orderId())).isZero();
    }

    @Test
    void failedRealCourseClaimParticipantRollsBackOrderEnrollmentVoucherLedgerAndCompletion() throws Exception {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        Purchase fixture = checkout(true);
        String authorization = bearer(fixture.user());
        createPaymentSession(fixture.orderId(), authorization);
        PaymentAttemptJpaEntity attempt = attempts.findAllByOrderIdAndUserId(fixture.orderId(), fixture.user().getId())
                .getFirst();
        CallbackTimes callbackTimes = callbackTimesFor(attempt);
        Instant paidAt = callbackTimes.providerPaidAt();
        postIpn(signedCallback(attempt.getMerchantReference(), attempt.getExpectedAmountVnd(), paidAt));
        Integer eventId = jdbc.queryForObject("SELECT id FROM payment_events WHERE order_id=?", Integer.class,
                fixture.orderId());
        assertOnlyClaimableWorkIs(eventId);

        // Make the real persisted claim terminal using a schema-valid test fixture transition.
        assertThat(jdbc.update("UPDATE course_claims SET status='RELEASED', active_key=NULL, "
                + "released_at=UTC_TIMESTAMP(6), release_reason='EXPIRED' WHERE order_id=? AND status='RESERVED'",
                fixture.orderId())).isEqualTo(1);
        var lease = workClaims.claimNext("course-v13-failing-claim").orElseThrow();
        SettlementResult result = settlement.executeClaimedWork(lease);

        assertThat(result.outcome()).isEqualTo(SettlementResult.Outcome.REQUIRES_REVIEW);
        assertThat(orders.findByIdAndUserId(fixture.orderId(), fixture.user().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?",
                Integer.class, fixture.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=?", Integer.class,
                fixture.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class,
                fixture.orderId())).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT state FROM payment_settlement_work_items WHERE payment_event_id=?",
                String.class, eventId)).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void staleLeaseCannotCompleteFulfillmentAndFreshLeaseSettlesOnce() throws Exception {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        Purchase fixture = checkout(false);
        String authorization = bearer(fixture.user());
        createPaymentSession(fixture.orderId(), authorization);
        PaymentAttemptJpaEntity attempt = attempts.findAllByOrderIdAndUserId(fixture.orderId(), fixture.user().getId())
                .getFirst();
        CallbackTimes callbackTimes = callbackTimesFor(attempt);
        Instant paidAt = callbackTimes.providerPaidAt();
        postIpn(signedCallback(attempt.getMerchantReference(), attempt.getExpectedAmountVnd(), paidAt));
        Integer eventId = jdbc.queryForObject("SELECT id FROM payment_events WHERE order_id=?", Integer.class,
                fixture.orderId());
        assertOnlyClaimableWorkIs(eventId);

        var oldLease = workClaims.claimNext("course-v13-old-owner").orElseThrow();
        assertThat(jdbc.update("UPDATE payment_settlement_work_items SET lease_until=DATE_SUB(UTC_TIMESTAMP(6), "
                + "INTERVAL 1 SECOND) WHERE id=? AND state='LEASED' AND lease_token=?",
                oldLease.id(), oldLease.leaseToken())).isEqualTo(1);
        var newLease = workClaims.claimNext("course-v13-new-owner").orElseThrow();
        assertThat(newLease.leaseToken()).isNotEqualTo(oldLease.leaseToken());

        SettlementResult stale = settlement.executeClaimedWork(oldLease);
        assertThat(stale.reason()).isEqualTo(SettlementResult.Reason.STALE_WORK_CLAIM);
        assertThat(orders.findByIdAndUserId(fixture.orderId(), fixture.user().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(settlement.executeClaimedWork(newLease).outcome()).isEqualTo(SettlementResult.Outcome.SETTLED);
        assertThat(settlement.executeClaimedWork(newLease).outcome()).isEqualTo(SettlementResult.Outcome.ALREADY_SETTLED);
        assertThat(settlements.findByOrderId(fixture.orderId())).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments "
                + "WHERE source_order_id=? AND source_order_item_id=?", Integer.class,
                fixture.orderId(), fixture.orderItemId())).isEqualTo(1);
    }

    @Test
    void providerAndReceiptAtExclusiveDeadlineDoNotCreateSettlementWork() throws Exception {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        Purchase providerBoundary = checkout(false);
        String firstAuth = bearer(providerBoundary.user());
        createPaymentSession(providerBoundary.orderId(), firstAuth);
        PaymentAttemptJpaEntity firstAttempt = attempts.findAllByOrderIdAndUserId(
                providerBoundary.orderId(), providerBoundary.user().getId()).getFirst();
        Instant deadline = orders.findByIdAndUserId(providerBoundary.orderId(), providerBoundary.user().getId())
                .orElseThrow().getPaymentExpiresAt();
        // VNPay PayDate has whole-second precision. Use the first representable instant at or
        // after the exclusive microsecond deadline, and ensure receipt is not before that evidence.
        Instant providerTimeAtOrAfterDeadline = deadline.truncatedTo(ChronoUnit.SECONDS);
        if (providerTimeAtOrAfterDeadline.isBefore(deadline)) {
            providerTimeAtOrAfterDeadline = providerTimeAtOrAfterDeadline.plusSeconds(1);
        }
        clock.set(providerTimeAtOrAfterDeadline);
        postIpn(signedCallback(firstAttempt.getMerchantReference(), firstAttempt.getExpectedAmountVnd(),
                providerTimeAtOrAfterDeadline));
        assertThat(jdbc.queryForObject("SELECT review_reason FROM payment_attempts WHERE id=?", String.class,
                firstAttempt.getId())).isEqualTo("LATE_SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlement_work_items w "
                + "JOIN payment_events e ON e.id=w.payment_event_id WHERE e.order_id=?", Integer.class,
                providerBoundary.orderId())).isZero();

        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        Purchase receiptBoundary = checkout(false);
        String secondAuth = bearer(receiptBoundary.user());
        createPaymentSession(receiptBoundary.orderId(), secondAuth);
        PaymentAttemptJpaEntity secondAttempt = attempts.findAllByOrderIdAndUserId(
                receiptBoundary.orderId(), receiptBoundary.user().getId()).getFirst();
        Instant secondDeadline = orders.findByIdAndUserId(receiptBoundary.orderId(), receiptBoundary.user().getId())
                .orElseThrow().getPaymentExpiresAt();
        clock.set(secondDeadline);
        postIpn(signedCallback(secondAttempt.getMerchantReference(), secondAttempt.getExpectedAmountVnd(),
                secondDeadline.minusSeconds(1)));
        assertThat(jdbc.queryForObject("SELECT review_reason FROM payment_attempts WHERE id=?", String.class,
                secondAttempt.getId())).isEqualTo("LATE_SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlement_work_items w "
                + "JOIN payment_events e ON e.id=w.payment_event_id WHERE e.order_id=?", Integer.class,
                receiptBoundary.orderId())).isZero();
    }

    @Test
    void persistedEligibleIpnEvidenceSurvivesConcurrentExpirationAndDuplicateReconciliation() throws Exception {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        Purchase fixture = checkout(true);
        String authorization = bearer(fixture.user());
        createPaymentSession(fixture.orderId(), authorization);
        PaymentAttemptJpaEntity attempt = attempts.findAllByOrderIdAndUserId(
                fixture.orderId(), fixture.user().getId()).getFirst();
        Instant deadline = orders.findByIdAndUserId(fixture.orderId(), fixture.user().getId())
                .orElseThrow().getPaymentExpiresAt();
        Instant providerPaidAt = deadline.minusSeconds(5);
        clock.set(deadline.minusSeconds(2));
        Map<String, String> callback = signedCallback(attempt.getMerchantReference(),
                attempt.getExpectedAmountVnd(), providerPaidAt);
        MvcResult initialIpn = postIpn(callback);
        assertThat(initialIpn.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(initialIpn.getResponse().getContentAsString()).path("RspCode").asText())
                .isEqualTo("00");
        Integer eventId = jdbc.queryForObject("SELECT id FROM payment_events WHERE order_id=?",
                Integer.class, fixture.orderId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection orderLock = dataSource.getConnection()) {
            orderLock.setAutoCommit(false);
            try (PreparedStatement lock = orderLock.prepareStatement("SELECT id FROM orders WHERE id=? FOR UPDATE")) {
                lock.setInt(1, fixture.orderId());
                try (ResultSet rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }

            clock.set(deadline);
            Future<ExpirationResult> expirationResult = pool.submit(() -> expiration.expire(fixture.orderId()));
            Future<MvcResult> duplicateIpn = pool.submit(() -> postIpn(callback));
            awaitInnoDbLockRequests("orders", 2);

            // Both independent transactions are confirmed waiting on the same Order row. Release the blocker;
            // whichever acquires it first must observe the already committed eligible event on the next read.
            orderLock.commit();
            ExpirationResult expiry = expirationResult.get(15, TimeUnit.SECONDS);
            MvcResult duplicate = duplicateIpn.get(15, TimeUnit.SECONDS);
            assertThat(expiry.outcome()).isEqualTo(ExpirationResult.Outcome.REQUIRES_REVIEW);
            assertThat(expiry.reason()).isEqualTo(ExpirationResult.Reason.PAYMENT_EVIDENCE);
            assertThat(duplicate.getResponse().getStatus()).isEqualTo(200);
            assertThat(objectMapper.readTree(duplicate.getResponse().getContentAsString()).path("RspCode").asText())
                    .isEqualTo("00");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE order_id=?", Integer.class,
                    fixture.orderId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlement_work_items "
                    + "WHERE payment_event_id=?", Integer.class, eventId)).isEqualTo(1);
            assertThat(orders.findByIdAndUserId(fixture.orderId(), fixture.user().getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(jdbc.queryForObject("SELECT status FROM course_claims WHERE order_id=?", String.class,
                    fixture.orderId())).isEqualTo("RESERVED");
            assertThat(jdbc.queryForObject("SELECT state FROM voucher_claims WHERE order_id=?", String.class,
                    fixture.orderId())).isEqualTo("RESERVED");

            // The callback is deliberately timestamped just before a simulated deadline 30 minutes
            // ahead of MySQL UTC. Evidence survives the expiration race, but its READY work is not
            // due on the database clock yet. Worker completion is covered by the other settlement tests.
            assertNoClaimableWork(eventId);
            assertThat(jdbc.queryForObject("SELECT state FROM payment_settlement_work_items WHERE payment_event_id=?",
                    String.class, eventId)).as(settlementDiagnostics(eventId)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlement_work_items "
                    + "WHERE payment_event_id=? AND available_at>UTC_TIMESTAMP(6)", Integer.class, eventId))
                    .as(settlementDiagnostics(eventId)).isEqualTo(1);
            assertThat(workClaims.claimNext("course-v13-ipn-expiration-race")).as(settlementDiagnostics(eventId)).isEmpty();
            assertThat(orders.findByIdAndUserId(fixture.orderId(), fixture.user().getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(settlements.findByOrderId(fixture.orderId())).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?",
                    Integer.class, fixture.orderId())).isZero();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitInnoDbLockRequests(String tableName, int minimumWaiters) throws Exception {
        String sql = "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks requested ON requested.ENGINE=w.ENGINE "
                + "AND requested.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                + "WHERE requested.OBJECT_SCHEMA=DATABASE() AND requested.OBJECT_NAME=? "
                + "AND requested.LOCK_STATUS='WAITING'";
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < timeout) {
            Integer count = jdbc.queryForObject(sql, Integer.class, tableName);
            if (count != null && count >= minimumWaiters) return;
            // Synchronize on InnoDB's explicit wait graph; the deadline only bounds a failed test.
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + minimumWaiters
                + " controlled InnoDB lock requests on " + tableName);
    }

    @Test
    void productAndMixedOrdersAreRejectedWithoutInventoryBeforeAnySettlementEffects() throws Exception {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        assertThat(applicationContext.getBeansOfType(InventoryReservationConfirmationPort.class)).isEmpty();
        User productBuyer = user();
        Integer productOrderId = directOrder(productBuyer, List.of(new OrderLineSnapshot(
                OrderItemType.PRODUCT, 990_001, "Unavailable inventory test product", 1, 15_000, false)), null);
        SettlementResult product = reconcileAndExecute(productOrderId, productBuyer);
        assertThat(product.reason()).isEqualTo(SettlementResult.Reason.FULFILLMENT_ADAPTER_UNAVAILABLE);
        assertThat(orders.findByIdAndUserId(productOrderId, productBuyer.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(settlements.findByOrderId(productOrderId)).isEmpty();

        User mixedBuyer = user();
        Course course = course();
        Integer courseId = Math.toIntExact(course.getId());
        Integer mixedOrderId = directOrder(mixedBuyer, List.of(
                new OrderLineSnapshot(OrderItemType.COURSE, courseId, course.getTitle(), 1, 20_000, true),
                new OrderLineSnapshot(OrderItemType.PRODUCT, 990_002, "Unavailable mixed product", 1, 5_000, false)),
                course);
        SettlementResult mixed = reconcileAndExecute(mixedOrderId, mixedBuyer);
        assertThat(mixed.reason()).isEqualTo(SettlementResult.Reason.FULFILLMENT_ADAPTER_UNAVAILABLE);
        assertThat(orders.findByIdAndUserId(mixedOrderId, mixedBuyer.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(jdbc.queryForObject("SELECT status FROM course_claims WHERE order_id=?", String.class,
                mixedOrderId)).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_course_enrollments WHERE source_order_id=?",
                Integer.class, mixedOrderId)).isZero();
        assertThat(settlements.findByOrderId(mixedOrderId)).isEmpty();
    }

    private Purchase checkout(boolean withVoucher) throws Exception {
        String email = "course-v13-" + UUID.randomUUID() + "@example.test";
        fixtureUserEmails.add(email);
        User user = users.saveAndFlush(User.builder().fullName("Course V13 integration")
                .email(email).passwordHash("test-only")
                .role(Role.USER).isActive(true).build());
        fixtureUserIds.add(user.getId());
        userProfiles.saveAndFlush(UserProfile.builder().user(user).profileType(ProfileType.LEARNER)
                .displayName(user.getFullName()).isApproved(true).build());
        assertThat(userProfiles.findByUserIdAndProfileType(user.getId(), ProfileType.LEARNER)).isPresent();
        Instant now = clock.instant();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Course course = new Course();
        course.setCode("V13-" + suffix);
        course.setSlug("v13-course-" + suffix);
        course.setTitle("V13 Course " + suffix);
        course.setPrice(30_000L);
        course.setLevel(CourseLevel.BASIC);
        course.setStatus(CourseStatus.PUBLISHED);
        course.setPublishedAt(LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC));
        fixtureCourseCodes.add(course.getCode());
        course = courses.saveAndFlush(course);
        fixtureCourseIds.add(course.getId());

        String voucherCode = null;
        Integer voucherId = null;
        if (withVoucher) {
            voucherCode = "V13-" + suffix.toUpperCase(Locale.ROOT);
            fixtureVoucherCodes.add(voucherCode);
            Voucher definition = new Voucher(voucherCode, OrderVoucher.Kind.FIXED_VND, 1_000,
                    OptionalLong.empty(), true, now.minusSeconds(3600), now.plusSeconds(86_400), 0,
                    OptionalLong.of(5), OptionalLong.of(2));
            VoucherJpaEntity row = vouchers.saveAndFlush(VoucherJpaEntity.fromDefinition(definition));
            voucherId = row.getId();
            fixtureVoucherIds.add(voucherId);
        }

        String authorization = bearer(user);
        MvcResult cartResponse = mvc.perform(post("/api/cart/items").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"COURSE\",\"itemId\":" + course.getId() + ",\"quantity\":1}"))
                .andReturn();
        assertThat(cartResponse.getResponse().getStatus()).isEqualTo(200);
        JsonNode cartBody = objectMapper.readTree(cartResponse.getResponse().getContentAsString()).path("data");
        int cartItemId = cartBody.path("items").get(0).path("cartItemId").asInt();
        long revision = cartBody.path("revision").asLong();
        String voucher = voucherCode == null ? "null" : "\"" + voucherCode + "\"";
        String body = "{\"cartRevision\":" + revision + ",\"selections\":[{\"cartItemId\":"
                + cartItemId + "}],\"voucherCode\":" + voucher + "}";
        String checkoutKey = "v13-checkout-" + UUID.randomUUID();
        MvcResult checkoutResponse = mvc.perform(post("/api/checkout/course-only")
                        .header("Authorization", authorization)
                        .header("Idempotency-Key", checkoutKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(checkoutResponse.getResponse().getStatus()).isEqualTo(201);
        int orderId = objectMapper.readTree(checkoutResponse.getResponse().getContentAsString())
                .path("data").path("orderId").asInt();
        fixtureOrderIds.add(orderId);
        int orderItemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Integer.class, orderId);
        return new Purchase(user, course, orderId, orderItemId, voucherId, voucherCode);
    }

    private List<MvcResult> concurrentSessionInitiations(Integer orderId, String authorization) throws Exception {
        synchronizePaymentClockWithOrder(orderId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> requests = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                requests.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("session barrier timeout");
                    return mvc.perform(post("/api/orders/{id}/payment-session", orderId)
                                    .header("Authorization", authorization)
                                    .with(request -> {
                                        request.setRemoteAddr("127.0.0.1");
                                        return request;
                                    }))
                            .andReturn();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(requests.get(0).get(15, TimeUnit.SECONDS), requests.get(1).get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void createPaymentSession(Integer orderId, String authorization) throws Exception {
        synchronizePaymentClockWithOrder(orderId);
        MvcResult result = mvc.perform(post("/api/orders/{id}/payment-session", orderId)
                        .header("Authorization", authorization)
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("resolved exception: %s; response body: %s", result.getResolvedException(),
                        result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void synchronizePaymentClockWithOrder(Integer orderId) {
        Integer ownerId = orders.findUserIdById(orderId).orElseThrow();
        Instant orderCreatedAt = orders.findByIdAndUserId(orderId, ownerId).orElseThrow().getCreatedAt();
        // Checkout uses its production UTC clock. Keep the independently frozen payment clock
        // at or after the persisted Order timestamp before creating the attempt snapshot.
        if (clock.instant().isBefore(orderCreatedAt)) clock.set(orderCreatedAt);
    }

    private Map<String, String> signedCallback(String merchantReference, long amountVnd, Instant paidAt) {
        TreeMap<String, String> values = new TreeMap<>();
        values.put("vnp_TmnCode", "DEMOV210");
        values.put("vnp_Amount", Long.toString(Math.multiplyExact(amountVnd, 100L)));
        values.put("vnp_BankCode", "NCB");
        values.put("vnp_OrderInfo", "course V13 integration verification");
        values.put("vnp_TransactionNo", Integer.toString(PROVIDER_TRANSACTION.incrementAndGet()));
        values.put("vnp_ResponseCode", "00");
        values.put("vnp_TransactionStatus", "00");
        values.put("vnp_TxnRef", merchantReference);
        values.put("vnp_PayDate", PAY_DATE.format(paidAt));
        String canonical = values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .reduce((left, right) -> left + "&" + right).orElseThrow();
        values.put("vnp_SecureHash", sign(canonical));
        return values;
    }

    private MvcResult postIpn(Map<String, String> parameters) throws Exception {
        var request = get("/api/payments/vnpay/ipn");
        parameters.forEach((key, value) -> request.param(key, value));
        return mvc.perform(request).andReturn();
    }

    private void assertOnlyClaimableWorkIs(Integer expectedEventId) {
        List<Integer> expected = expectedEventId == null ? List.of() : List.of(expectedEventId);
        assertClaimableWorkIs(expected, expectedEventId);
    }

    private void assertNoClaimableWork(Integer diagnosticEventId) {
        assertClaimableWorkIs(List.of(), diagnosticEventId);
    }

    private void assertClaimableWorkIs(List<Integer> expectedEventIds, Integer diagnosticEventId) {
        List<Integer> ids = jdbc.query("SELECT w.payment_event_id FROM payment_settlement_work_items w "
                        + "WHERE ((w.state IN ('READY','RETRY_WAIT') AND w.available_at<=UTC_TIMESTAMP(6) "
                        + "AND w.attempt_count<8) OR (w.state='LEASED' AND w.lease_until<=UTC_TIMESTAMP(6)) "
                        + "OR (w.state='LEASED' AND w.lease_until<=UTC_TIMESTAMP(6) AND w.attempt_count>=8)) "
                        + "ORDER BY w.id",
                (rs, row) -> rs.getInt(1));
        assertThat(ids).as("global work eligible for claim/review; %s", settlementDiagnostics(diagnosticEventId))
                .containsExactlyElementsOf(expectedEventIds);
    }

    private CallbackTimes callbackTimesFor(PaymentAttemptJpaEntity attempt) {
        Instant createdAt = attempt.getCreatedAt();
        Instant providerPaidAt = createdAt.truncatedTo(ChronoUnit.SECONDS);
        if (providerPaidAt.isBefore(createdAt)) providerPaidAt = providerPaidAt.plusSeconds(1);
        if (!providerPaidAt.isBefore(attempt.getExpiresAt()))
            throw new AssertionError("No whole-second provider payment time fits before the Order deadline");

        long timeoutAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Instant databaseNow = workClaimRepository.databaseUtcNow();
        while (databaseNow.isBefore(providerPaidAt)) {
            if (System.nanoTime() >= timeoutAt)
                throw new AssertionError("MySQL UTC did not reach the valid provider timestamp; "
                        + settlementDiagnosticsForAttempt(attempt.getId()));
            if (Thread.currentThread().isInterrupted())
                throw new AssertionError("Interrupted while synchronizing the test clock to MySQL UTC");
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            databaseNow = workClaimRepository.databaseUtcNow();
        }
        if (!databaseNow.isBefore(attempt.getExpiresAt()))
            throw new AssertionError("MySQL UTC reached the Order deadline before callback receipt; "
                    + settlementDiagnosticsForAttempt(attempt.getId()));
        clock.set(databaseNow);
        return new CallbackTimes(providerPaidAt, databaseNow);
    }

    private String settlementDiagnostics(Integer eventId) {
        if (eventId == null) return "databaseUtc=" + workClaimRepository.databaseUtcNow();
        List<Map<String, Object>> attemptsByEvent = jdbc.queryForList("SELECT a.id,a.status,a.review_reason,"
                + "a.created_at,a.updated_at,a.expires_at,a.succeeded_at FROM payment_attempts a "
                + "JOIN payment_events e ON e.attempt_id=a.id WHERE e.id=?", eventId);
        List<Map<String, Object>> events = jdbc.queryForList("SELECT id,outcome,disposition,reason,occurred_at,"
                + "received_at FROM payment_events WHERE id=?", eventId);
        List<Map<String, Object>> work = jdbc.queryForList("SELECT id,state,attempt_count,retry_generation,"
                + "available_at,lease_until,lease_owner,(lease_token IS NOT NULL) AS lease_token_present "
                + "FROM payment_settlement_work_items WHERE payment_event_id=?", eventId);
        return "databaseUtc=" + workClaimRepository.databaseUtcNow() + ", attempt=" + attemptsByEvent
                + ", event=" + events + ", work=" + work;
    }

    private String settlementDiagnosticsForAttempt(Integer attemptId) {
        List<Map<String, Object>> attempt = jdbc.queryForList("SELECT id,status,review_reason,created_at,updated_at,"
                + "expires_at,succeeded_at FROM payment_attempts WHERE id=?", attemptId);
        List<Map<String, Object>> event = jdbc.queryForList("SELECT id,outcome,disposition,reason,occurred_at,"
                + "received_at FROM payment_events WHERE attempt_id=?", attemptId);
        List<Map<String, Object>> work = jdbc.queryForList("SELECT w.id,w.state,w.attempt_count,w.retry_generation,"
                + "w.available_at,w.lease_until,w.lease_owner,(w.lease_token IS NOT NULL) AS lease_token_present "
                + "FROM payment_settlement_work_items w JOIN payment_events e ON e.id=w.payment_event_id "
                + "WHERE e.attempt_id=?", attemptId);
        return "databaseUtc=" + workClaimRepository.databaseUtcNow() + ", attempt=" + attempt
                + ", event=" + event + ", work=" + work;
    }

    private User user() {
        String email = "course-v13-direct-" + UUID.randomUUID() + "@example.test";
        fixtureUserEmails.add(email);
        User user = users.saveAndFlush(User.builder().fullName("Course V13 direct fixture")
                .email(email).passwordHash("test-only")
                .role(Role.USER).isActive(true).build());
        fixtureUserIds.add(user.getId());
        userProfiles.saveAndFlush(UserProfile.builder().user(user).profileType(ProfileType.LEARNER)
                .displayName(user.getFullName()).isApproved(true).build());
        assertThat(userProfiles.findByUserIdAndProfileType(user.getId(), ProfileType.LEARNER)).isPresent();
        return user;
    }

    private Course course() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Course course = new Course();
        course.setCode("V13D-" + suffix);
        course.setSlug("v13-direct-" + suffix);
        course.setTitle("Direct Course " + suffix);
        course.setPrice(20_000L);
        course.setLevel(CourseLevel.BASIC);
        course.setStatus(CourseStatus.PUBLISHED);
        course.setPublishedAt(LocalDateTime.ofInstant(clock.instant().minusSeconds(60), ZoneOffset.UTC));
        fixtureCourseCodes.add(course.getCode());
        course = courses.saveAndFlush(course);
        fixtureCourseIds.add(course.getId());
        return course;
    }

    private Integer directOrder(User user, List<OrderLineSnapshot> lines, Course claimCourse) {
        Instant createdAt = clock.instant().minusSeconds(10);
        Order snapshot = Order.create(user.getId(), lines, 0, Optional.empty(), Clock.fixed(createdAt, ZoneOffset.UTC));
        return new TransactionTemplate(transactionManager).execute(status -> {
            OrderJpaEntity row = OrderJpaEntity.fromSnapshot(snapshot);
            entityManager.persist(row);
            entityManager.flush();
            fixtureOrderIds.add(row.getId());
            if (claimCourse != null) {
                OrderItemJpaEntity item = row.getItems().stream()
                        .filter(candidate -> candidate.getItemType() == OrderItemType.COURSE).findFirst().orElseThrow();
                courseClaims.reserve(new CourseClaimContracts.ReservationCommand(user.getId(), row.getId(), List.of(
                        new CourseClaimContracts.ClaimLine(item.getId(), claimCourse.getId(),
                                CourseClaimContracts.ClaimSourceType.STANDALONE_COURSE, null)), createdAt,
                        snapshot.getExpiresAt()));
            }
            PaymentAttempt attempt = PaymentAttempt.create(row.getId(), snapshot, "vnpay",
                    "course-v13-direct-" + UUID.randomUUID().toString().replace("-", ""),
                    Clock.fixed(createdAt.plusMillis(1), ZoneOffset.UTC));
            entityManager.persist(PaymentAttemptJpaEntity.newSnapshot(attempt));
            entityManager.flush();
            return row.getId();
        });
    }

    private SettlementResult reconcileAndExecute(Integer orderId, User user) throws Exception {
        PaymentAttemptJpaEntity attempt = attempts.findAllByOrderIdAndUserId(orderId, user.getId()).getFirst();
        CallbackTimes callbackTimes = callbackTimesFor(attempt);
        postIpn(signedCallback(attempt.getMerchantReference(), attempt.getExpectedAmountVnd(),
                callbackTimes.providerPaidAt()));
        Integer eventId = jdbc.queryForObject("SELECT id FROM payment_events WHERE order_id=?", Integer.class, orderId);
        assertOnlyClaimableWorkIs(eventId);
        var claim = workClaims.claimNext("course-v13-product-gate");
        assertThat(claim).as(settlementDiagnostics(eventId)).isPresent();
        return settlement.executeClaimedWork(claim.orElseThrow());
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record CallbackTimes(Instant providerPaidAt, Instant receivedAt) { }

    private record Purchase(User user, Course course, Integer orderId, Integer orderItemId,
                            Integer voucherId, String voucherCode) { }
}
