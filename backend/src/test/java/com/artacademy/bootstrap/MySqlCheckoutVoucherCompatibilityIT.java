package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.StoredOrder;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.checkout.VoucherReservationPort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guarded real-MySQL Checkout/Voucher integration tests. Never run against drawing_course_dev. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles({"test", "mysql-it"})
@ContextConfiguration(initializers = MySqlSchemaCompatibilityIT.TargetGuard.class)
@Transactional
class MySqlCheckoutVoucherCompatibilityIT {
    private static final Instant NOW = Instant.parse("2026-10-28T05:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private VoucherClaimRepository claims;
    @Autowired private OrderRepository orders;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JpaVoucherReservationService reservations;

    @BeforeEach
    void requireExpectedTargetAndV1ThroughV7() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("drawing_course_it");
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IN ('1','2','3','4','5','6','7')", Integer.class)).isEqualTo(7);
    }

    @Test
    void v7CheckoutRequestSchemaMatchesEntityAndEnforcesConstraints() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='checkout_requests'", Integer.class)).isEqualTo(1);
        assertColumn("id", "int", "NO", null);
        assertColumn("user_id", "int", "NO", null);
        assertColumn("idempotency_key", "varchar", "NO", 64);
        assertColumn("request_fingerprint", "varchar", "NO", 67);
        assertColumn("state", "varchar", "NO", 16);
        assertColumn("order_id", "int", "YES", null);
        for (String timestamp : List.of("created_at", "updated_at")) assertTimestamp(timestamp, false);
        assertTimestamp("completed_at", true);
        assertUniqueIndex("uk_checkout_requests_user_key", "user_id,idempotency_key");
        assertUniqueIndex("uk_checkout_requests_order", "order_id");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='checkout_requests' AND CONSTRAINT_NAME IN ('fk_checkout_requests_user','fk_checkout_requests_order') AND REFERENCED_TABLE_NAME IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='checkout_requests' AND CONSTRAINT_TYPE='CHECK' AND CONSTRAINT_NAME IN ('ck_checkout_requests_state','ck_checkout_requests_completion')", Integer.class)).isEqualTo(2);

        int userId = insertUser();
        int orderId = insertOrder(userId);
        String key = "v7-" + UUID.randomUUID();
        jdbc.update("INSERT INTO checkout_requests(user_id,idempotency_key,request_fingerprint,state,created_at,updated_at) VALUES (?,?,?,'PROCESSING',NOW(6),NOW(6))",
                userId, key, "v1:" + "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO checkout_requests(user_id,idempotency_key,request_fingerprint,state,created_at,updated_at) VALUES (?,?,?,'PROCESSING',NOW(6),NOW(6))",
                userId, key, "v1:" + "b".repeat(64))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        String completedKey = "complete-" + UUID.randomUUID();
        jdbc.update("INSERT INTO checkout_requests(user_id,idempotency_key,request_fingerprint,state,order_id,created_at,updated_at,completed_at) VALUES (?,?,?,'COMPLETED',?,NOW(6),NOW(6),NOW(6))",
                userId, completedKey, "v1:" + "c".repeat(64), orderId);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO checkout_requests(user_id,idempotency_key,request_fingerprint,state,order_id,created_at,updated_at,completed_at) VALUES (?,?,?,'COMPLETED',?,NOW(6),NOW(6),NOW(6))",
                userId, "order-" + UUID.randomUUID(), "v1:" + "c".repeat(64), orderId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertMysqlCheckConstraintViolation(() -> jdbc.update("INSERT INTO checkout_requests(user_id,idempotency_key,request_fingerprint,state,created_at,updated_at) VALUES (?,?,?,'COMPLETED',NOW(6),NOW(6))",
                userId, "invalid-" + UUID.randomUUID(), "v1:" + "d".repeat(64)), "ck_checkout_requests_completion");
        // INVALID violates both checks: the state check rejects it, and the completion check
        // also excludes every state other than PROCESSING/COMPLETED. MySQL may report either.
        assertMysqlCheckConstraintViolation(() -> jdbc.update("INSERT INTO checkout_requests(user_id,idempotency_key,request_fingerprint,state,created_at,updated_at) VALUES (?,?,?,'INVALID',NOW(6),NOW(6))",
                userId, "state-" + UUID.randomUUID(), "v1:" + "e".repeat(64)),
                "ck_checkout_requests_state", "ck_checkout_requests_completion");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO checkout_requests(user_id,idempotency_key,request_fingerprint,state,created_at,updated_at) VALUES (?,?,?,'PROCESSING',NOW(6),NOW(6))",
                Integer.MAX_VALUE, "fk-" + UUID.randomUUID(), "v1:" + "f".repeat(64))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO checkout_requests(user_id,idempotency_key,request_fingerprint,state,order_id,created_at,updated_at,completed_at) VALUES (?,?,?,'COMPLETED',?,NOW(6),NOW(6),NOW(6))",
                userId, "order-fk-" + UUID.randomUUID(), "v1:" + "f".repeat(64), Integer.MAX_VALUE)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentTotalQuotaOneAllowsAtMostOneVoucherReservation() throws Exception {
        var pair = fixturePair(false, 1, 5);
        try {
            assertConcurrentReservation(pair.first, pair.second, "total");
            assertThat(voucherValue("reserved_count", pair.first.voucherId)).isEqualTo(1L);
            assertThat(claimCount(pair.first.voucherId)).isEqualTo(1L);
        } finally { deleteFixtures(pair.first, pair.second); }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPerUserLimitOneAllowsAtMostOneReservationForSameUser() throws Exception {
        var pair = fixturePair(true, 5, 1);
        try {
            assertConcurrentReservation(pair.first, pair.second, "user");
            assertThat(userUsageValue("reserved_count", pair.first.voucherId, pair.first.userId)).isEqualTo(1L);
            assertThat(claimCount(pair.first.voucherId)).isEqualTo(1L);
        } finally { deleteFixtures(pair.first, pair.second); }
    }

    @Test
    void duplicateReservationAndReleaseOrConsumeRetriesDoNotDoubleAdjustCounters() {
        Fixture released = fixture(voucher("release-" + UUID.randomUUID(), 5, 5), null);
        reserveTwice(released);
        assertThat(claimCount(released.voucherId)).isEqualTo(1L);
        inTx(() -> {
            jdbc.update("UPDATE orders SET status='CANCELLED',payment_status='CANCELLED' WHERE id=?", released.orderId);
            em.clear();
            reservations.releaseForOrder(released.userId, released.orderId, VoucherClaim.ReleaseReason.CANCELLED, NOW.plusSeconds(1));
            reservations.releaseForOrder(released.userId, released.orderId, VoucherClaim.ReleaseReason.CANCELLED, NOW.plusSeconds(2));
        });
        assertThat(voucherValue("reserved_count", released.voucherId)).isZero();
        assertThat(userUsageValue("reserved_count", released.voucherId, released.userId)).isZero();

        Fixture redeemed = fixture(voucher("redeem-" + UUID.randomUUID(), 5, 5), null);
        reserveTwice(redeemed);
        assertThat(claimCount(redeemed.voucherId)).isEqualTo(1L);
        inTx(() -> {
            jdbc.update("UPDATE orders SET status='CONFIRMED',payment_status='SUCCEEDED' WHERE id=?", redeemed.orderId);
            em.clear();
            reservations.consumeForOrder(redeemed.userId, redeemed.orderId, "mysql-it-" + UUID.randomUUID(), NOW.plusSeconds(1), NOW.plusSeconds(2));
            // Reuse the exact evidence identity to exercise idempotent settlement retry.
            var claim = claims.findByOrderIdAndUserId(redeemed.orderId, redeemed.userId).orElseThrow();
            reservations.consumeForOrder(redeemed.userId, redeemed.orderId, claim.getPaymentReference(), NOW.plusSeconds(1), NOW.plusSeconds(2));
        });
        assertThat(voucherValue("reserved_count", redeemed.voucherId)).isZero();
        assertThat(voucherValue("redeemed_count", redeemed.voucherId)).isEqualTo(1L);
        assertThat(userUsageValue("reserved_count", redeemed.voucherId, redeemed.userId)).isZero();
        assertThat(userUsageValue("redeemed_count", redeemed.voucherId, redeemed.userId)).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void voucherReservationRollbackRestoresClaimAndBothUsageCounters() {
        Fixture fixture = fixture(voucher("rollback-" + UUID.randomUUID(), 5, 5), null);
        assertThat(voucherValue("reserved_count", fixture.voucherId)).isZero();
        assertThat(userUsageValue("reserved_count", fixture.voucherId, fixture.userId)).isZero();
        try {
            assertThatThrownBy(() -> inTx(() -> {
                reserveOnce(fixture);
                throw new IllegalStateException("rollback after reservation");
            })).isInstanceOf(IllegalStateException.class);
            // No ambient test transaction: these queries observe fresh committed state after rollback.
            assertThat(claimCount(fixture.voucherId)).isZero();
            assertThat(voucherValue("reserved_count", fixture.voucherId)).isZero();
            assertThat(userUsageValue("reserved_count", fixture.voucherId, fixture.userId)).isZero();
        } finally { deleteFixture(fixture); }
    }

    private void assertConcurrentReservation(Fixture first, Fixture second, String quotaLabel) throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstTask = pool.submit(() -> inTxResult(() -> {
                var quote = reservations.quoteAndLock(first.userId, first.code, 10_000, NOW).orElseThrow();
                firstLocked.countDown();
                await(releaseFirst);
                reservations.reserveForOrder(first.userId, first.stored, first.order, quote, NOW);
                return true;
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var secondTask = pool.submit(() -> inTxResult(() -> {
                secondAttempting.countDown();
                var quote = reservations.quoteAndLock(second.userId, second.code, 10_000, NOW);
                quote.ifPresent(q -> reservations.reserveForOrder(second.userId, second.stored, second.order, q, NOW));
                return quote.isPresent();
            }));
            assertThat(secondAttempting.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> secondTask.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class)
                        .as("second %s-quota transaction must wait on the locked voucher row", quotaLabel);
            } finally {
                releaseFirst.countDown();
            }
            assertThat(firstTask.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondTask.get(10, TimeUnit.SECONDS)).isFalse();
        }
    }

    private Pair fixturePair(boolean sameUser, long totalLimit, long userLimit) {
        String code = "mysql-it-" + UUID.randomUUID();
        Voucher definition = voucher(code, totalLimit, userLimit);
        Fixture first = fixture(definition, null);
        Fixture second = fixtureForExistingVoucher(first.voucherId, first.code, sameUser ? first.userId : null);
        return new Pair(first, second);
    }

    private Fixture fixtureForExistingVoucher(Integer voucherId, String code, Integer existingUserId) {
        return inTxResult(() -> {
            Integer userId = existingUserId == null ? users.saveAndFlush(User.builder().fullName("MySQL IT")
                    .email("mysql-it-" + UUID.randomUUID() + "@example.test").passwordHash("test-only")
                    .role(Role.USER).isActive(true).build()).getId() : existingUserId;
            return createOrder(voucherId, code, userId);
        });
    }

    private Fixture fixture(Voucher voucher, Integer existingUserId) {
        return inTxResult(() -> {
            Integer userId = existingUserId == null ? users.saveAndFlush(User.builder().fullName("MySQL IT")
                    .email("mysql-it-" + UUID.randomUUID() + "@example.test").passwordHash("test-only")
                    .role(Role.USER).isActive(true).build()).getId() : existingUserId;
            VoucherJpaEntity voucherRow = VoucherJpaEntity.fromDefinition(voucher);
            em.persist(voucherRow);
            em.flush();
            return createOrder(voucherRow.getId(), voucher.code(), userId);
        });
    }

    private Fixture createOrder(Integer voucherId, String code, Integer userId) {
        VoucherJpaEntity voucherRow = em.find(VoucherJpaEntity.class, voucherId);
        Voucher definition = new Voucher(code, voucherRow.getKind(), voucherRow.getValue(),
                voucherRow.getPercentageCapVnd() == null ? OptionalLong.empty() : OptionalLong.of(voucherRow.getPercentageCapVnd()),
                voucherRow.isActive(), voucherRow.getValidFrom(), voucherRow.getValidUntil(), voucherRow.getMinimumSubtotalVnd(),
                voucherRow.getTotalUsageLimit() == null ? OptionalLong.empty() : OptionalLong.of(voucherRow.getTotalUsageLimit()),
                voucherRow.getPerUserUsageLimit() == null ? OptionalLong.empty() : OptionalLong.of(voucherRow.getPerUserUsageLimit()));
        Order order = Order.create(userId, List.of(new OrderLineSnapshot(OrderItemType.COURSE, 701,
                "MySQL IT course", 1, 10_000, true)), 0, Optional.of(definition.toOrderSnapshot()), CLOCK);
        OrderJpaEntity orderRow = OrderJpaEntity.fromSnapshot(order);
        em.persist(orderRow);
        em.flush();
        return new Fixture(userId, voucherId, code, orderRow.getId(), order, new OrderPersistenceMapper().toStored(orderRow));
    }

    private void reserveTwice(Fixture fixture) {
        inTx(() -> {
            var quote = reservations.quoteAndLock(fixture.userId, fixture.code, 10_000, NOW).orElseThrow();
            reservations.reserveForOrder(fixture.userId, fixture.stored, fixture.order, quote, NOW);
            reservations.reserveForOrder(fixture.userId, fixture.stored, fixture.order, quote, NOW);
        });
    }

    private void reserveOnce(Fixture fixture) {
        var quote = reservations.quoteAndLock(fixture.userId, fixture.code, 10_000, NOW).orElseThrow();
        reservations.reserveForOrder(fixture.userId, fixture.stored, fixture.order, quote, NOW);
    }

    private Voucher voucher(String code, long totalLimit, long userLimit) {
        return new Voucher(code, OrderVoucher.Kind.FIXED_VND, 1_000, OptionalLong.empty(), true,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), 0, OptionalLong.of(totalLimit), OptionalLong.of(userLimit));
    }

    private int insertUser() {
        return generated("INSERT INTO users(full_name,email,password_hash,role,is_active,created_at,updated_at) VALUES ('mysql-it',?,'test-only','USER',TRUE,NOW(6),NOW(6))",
                "mysql-it-v7-" + UUID.randomUUID() + "@example.test");
    }

    private int insertOrder(int userId) {
        LocalDateTime created = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return generated("INSERT INTO orders(version,user_id,currency,status,payment_status,merchandise_subtotal_vnd,eligible_subtotal_vnd,discount_vnd,shipping_fee_vnd,total_vnd,payment_expires_at,created_at,updated_at) VALUES (0,?,'VND','PENDING_PAYMENT','CREATED',100,100,0,0,100,?,?,?)",
                userId, created.plusMinutes(30), created, created);
    }

    private int generated(String sql, Object... args) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    private void assertColumn(String name, String type, String nullable, Integer length) {
        String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='checkout_requests' AND COLUMN_NAME=? AND DATA_TYPE=? AND IS_NULLABLE=?";
        assertThat(jdbc.queryForObject(sql, Integer.class, name, type, nullable)).as("column %s metadata", name).isEqualTo(1);
        if (length != null) assertThat(jdbc.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='checkout_requests' AND COLUMN_NAME=?", Integer.class, name)).isEqualTo(length);
    }

    private void assertTimestamp(String name, boolean nullable) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='checkout_requests' AND COLUMN_NAME=? AND DATA_TYPE='datetime' AND DATETIME_PRECISION=6 AND IS_NULLABLE=?",
                Integer.class, name, nullable ? "YES" : "NO")).as("UTC timestamp column %s", name).isEqualTo(1);
    }

    private void assertUniqueIndex(String name, String columns) {
        assertThat(jdbc.queryForObject("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='checkout_requests' AND INDEX_NAME=? AND NON_UNIQUE=0",
                String.class, name)).isEqualTo(columns);
    }

    private long claimCount(Integer voucherId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM voucher_claims WHERE voucher_id=?", Long.class, voucherId);
    }

    private long voucherValue(String column, Integer voucherId) {
        return jdbc.queryForObject("SELECT " + column + " FROM vouchers WHERE id=?", Long.class, voucherId);
    }

    private long userUsageValue(String column, Integer voucherId, Integer userId) {
        return jdbc.queryForObject("SELECT COALESCE((SELECT " + column + " FROM voucher_user_usage WHERE voucher_id=? AND user_id=?),0)",
                Long.class, voucherId, userId);
    }

    private void assertMysqlCheckConstraintViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
                                                       String... expectedConstraintNames) {
        org.junit.jupiter.api.Assertions.assertTrue(expectedConstraintNames.length > 0,
                "at least one specific CHECK constraint must be expected");
        String expectedNames = String.join(" or ", expectedConstraintNames);
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(operation);
        assertThat(failure).as("invalid data must be rejected by MySQL CHECK %s", expectedNames).isNotNull();
        java.sql.SQLException sqlFailure = findSqlException(failure);
        org.junit.jupiter.api.Assertions.assertNotNull(sqlFailure, "failure retains MySQL SQLException details");
        org.junit.jupiter.api.Assertions.assertEquals(3819, sqlFailure.getErrorCode(), "MySQL CHECK violation code");
        org.junit.jupiter.api.Assertions.assertEquals("HY000", sqlFailure.getSQLState(), "MySQL CHECK violation SQLState");
        String message = sqlFailure.getMessage();
        org.junit.jupiter.api.Assertions.assertNotNull(message, "MySQL CHECK failure includes a diagnostic message");
        boolean expectedConstraintReported = java.util.Arrays.stream(expectedConstraintNames).anyMatch(message::contains);
        org.junit.jupiter.api.Assertions.assertTrue(expectedConstraintReported,
                "MySQL must report one of the CHECK constraints violated by this INSERT; expected " + expectedNames
                        + ", actual message: " + message);
    }

    private java.sql.SQLException findSqlException(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sqlException) return sqlException;
        }
        return null;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("MySQL transaction coordination timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void deleteFixtures(Fixture first, Fixture second) {
        inTx(() -> {
            jdbc.update("DELETE FROM voucher_claims WHERE order_id IN (?,?)", first.orderId, second.orderId);
            jdbc.update("DELETE FROM order_items WHERE order_id IN (?,?)", first.orderId, second.orderId);
            jdbc.update("DELETE FROM orders WHERE id IN (?,?)", first.orderId, second.orderId);
            jdbc.update("DELETE FROM voucher_user_usage WHERE voucher_id=? AND user_id IN (?,?)", first.voucherId, first.userId, second.userId);
            jdbc.update("DELETE FROM vouchers WHERE id=?", first.voucherId);
            if (!first.userId.equals(second.userId)) jdbc.update("DELETE FROM users WHERE id IN (?,?)", first.userId, second.userId);
            else jdbc.update("DELETE FROM users WHERE id=?", first.userId);
        });
    }

    private void deleteFixture(Fixture fixture) {
        inTx(() -> {
            jdbc.update("DELETE FROM voucher_claims WHERE order_id=?", fixture.orderId);
            jdbc.update("DELETE FROM order_items WHERE order_id=?", fixture.orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", fixture.orderId);
            jdbc.update("DELETE FROM voucher_user_usage WHERE voucher_id=? AND user_id=?", fixture.voucherId, fixture.userId);
            jdbc.update("DELETE FROM vouchers WHERE id=?", fixture.voucherId);
            jdbc.update("DELETE FROM users WHERE id=?", fixture.userId);
        });
    }

    private void inTx(Runnable action) { new TransactionTemplate(transactionManager).executeWithoutResult(s -> action.run()); }
    private <T> T inTxResult(java.util.function.Supplier<T> action) { return new TransactionTemplate(transactionManager).execute(s -> action.get()); }

    private record Fixture(Integer userId, Integer voucherId, String code, Integer orderId, Order order, StoredOrder stored) { }
    private record Pair(Fixture first, Fixture second) { }
}
