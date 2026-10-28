package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.CartJpaEntity;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderJpaEntity;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.entity.OrderUtcInstantConverter;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.entity.PaymentAttempt;
import com.drawingcourse.backend.entity.PaymentAttemptJpaEntity;
import com.drawingcourse.backend.entity.PaymentUtcInstantConverter;
import com.drawingcourse.backend.entity.Voucher;
import com.drawingcourse.backend.entity.VoucherJpaEntity;
import com.drawingcourse.backend.repository.CartItemRepository;
import com.drawingcourse.backend.repository.CartRepository;
import com.drawingcourse.backend.service.CartApplicationService;
import com.drawingcourse.backend.service.CartCatalog;
import com.drawingcourse.backend.entity.VoucherUtcInstantConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import jakarta.persistence.EntityManager;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in, real-MySQL schema contract tests. Never run against drawing_course_dev.
 * Invoke only via {@code mvn -Pmysql-it verify} after preparing the isolated drawing_course_it DB.
 */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles({"test", "mysql-it"})
@ContextConfiguration(initializers = MySqlSchemaCompatibilityIT.TargetGuard.class)
@Transactional
class MySqlSchemaCompatibilityIT {
    private static final String EXPECTED_DATABASE = "drawing_course_it";
    private static final String EXPECTED_URL = "jdbc:mysql://127.0.0.1:3306/drawing_course_it";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConfigurableEnvironment environment;
    @Autowired private EntityManager entityManager;
    @Autowired private CartRepository carts;
    @Autowired private CartItemRepository cartItems;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void requireExpectedTargetAndMigratedSchema() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(EXPECTED_DATABASE);
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        String mysqlVersion = jdbc.queryForObject("SELECT VERSION()", String.class);
        var version = java.util.regex.Pattern.compile("^8\\.0\\.(\\d+)(?:[-+].*)?$").matcher(mysqlVersion);
        assertThat(version.matches()).as("MySQL 8.0.16+ is required for enforced CHECK constraints").isTrue();
        assertThat(Integer.parseInt(version.group(1))).isGreaterThanOrEqualTo(16);
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.clean-disabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE AND version IN ('1','2','3','4','5','6','7','8')", Integer.class)).isEqualTo(8);
    }

    @Test
    void flywaySchemaValidatesAndUsesExpectedMysqlMappings() throws Exception {
        assertColumn("users", "role", Types.VARCHAR, null);
        assertColumn("user_profiles", "profile_type", Types.VARCHAR, null);
        assertColumn("user_profiles", "age_group", Types.VARCHAR, null);
        assertColumn("orders", "total_vnd", Types.BIGINT, null);
        assertColumn("orders", "created_at", Types.TIMESTAMP, 6);
        assertColumn("payment_attempts", "expires_at", Types.TIMESTAMP, 6);
        assertColumn("carts", "revision", Types.BIGINT, null);
        assertThat(jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='carts' AND COLUMN_NAME='revision'", String.class)).isEqualTo("0");
        assertThat(columnCollation("users", "email")).isEqualTo("utf8mb4_0900_as_ci");
        assertThat(columnCollation("vouchers", "code")).isEqualTo("utf8mb4_bin");
        assertThat(columnCollation("payment_attempts", "merchant_reference")).isEqualTo("utf8mb4_bin");
        assertThat(columnCollation("payment_events", "event_reference")).isEqualTo("utf8mb4_bin");
        assertV8Schema();
    }

    private void assertV8Schema() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('payment_settlements','payment_reconciliation_cases')", Integer.class)).isEqualTo(2);
        assertColumn("payment_settlements", "order_id", Types.INTEGER, null);
        assertColumn("payment_settlements", "payment_attempt_id", Types.INTEGER, null);
        assertColumn("payment_settlements", "payment_event_id", Types.INTEGER, null);
        assertColumn("payment_settlements", "provider_paid_at", Types.TIMESTAMP, 6);
        assertColumn("payment_settlements", "settled_at", Types.TIMESTAMP, 6);
        assertColumn("payment_reconciliation_cases", "received_at", Types.TIMESTAMP, 6);
        assertColumn("payment_reconciliation_cases", "provider_paid_at", Types.TIMESTAMP, 6);
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_reconciliation_cases' AND COLUMN_NAME='order_id'", String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_reconciliation_cases' AND COLUMN_NAME='evidence_fingerprint'", Integer.class)).isEqualTo(67);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='payment_settlements' AND CONSTRAINT_TYPE='UNIQUE' AND CONSTRAINT_NAME IN ('uk_payment_settlement_order','uk_payment_settlement_attempt','uk_payment_settlement_event')", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='payment_reconciliation_cases' AND CONSTRAINT_TYPE='UNIQUE' AND CONSTRAINT_NAME IN ('uk_payment_reconciliation_evidence','uk_payment_reconciliation_conflict')", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND ((TABLE_NAME='payment_attempts' AND CONSTRAINT_NAME='uk_payment_attempt_id_order') OR (TABLE_NAME='payment_events' AND CONSTRAINT_NAME='uk_payment_event_id_attempt_order') OR (TABLE_NAME='payment_event_conflicts' AND CONSTRAINT_NAME='uk_payment_conflict_id_event_attempt_order')) AND CONSTRAINT_TYPE='UNIQUE'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='payment_reconciliation_cases' AND CONSTRAINT_TYPE='CHECK' AND CONSTRAINT_NAME LIKE 'ck_payment_reconciliation_%'", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='payment_settlements' AND CONSTRAINT_TYPE='FOREIGN KEY' AND CONSTRAINT_NAME IN ('fk_payment_settlement_order','fk_payment_settlement_attempt_order','fk_payment_settlement_event_route')", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='payment_reconciliation_cases' AND CONSTRAINT_TYPE='FOREIGN KEY' AND CONSTRAINT_NAME IN ('fk_payment_reconciliation_attempt','fk_payment_reconciliation_order','fk_payment_reconciliation_event','fk_payment_reconciliation_conflict','fk_payment_reconciliation_event_route','fk_payment_reconciliation_conflict_route')", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_settlements' AND REFERENCED_TABLE_NAME IS NOT NULL", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_reconciliation_cases' AND REFERENCED_TABLE_NAME IS NOT NULL", Integer.class)).isEqualTo(11);
        assertForeignKeyRoute("payment_settlements", "fk_payment_settlement_attempt_order", "payment_attempt_id>id,order_id>order_id");
        assertForeignKeyRoute("payment_settlements", "fk_payment_settlement_event_route", "payment_event_id>id,payment_attempt_id>attempt_id,order_id>order_id");
        assertForeignKeyRoute("payment_reconciliation_cases", "fk_payment_reconciliation_event_route", "payment_event_id>id,payment_attempt_id>attempt_id,order_id>order_id");
        assertForeignKeyRoute("payment_reconciliation_cases", "fk_payment_reconciliation_conflict_route", "payment_event_conflict_id>id,payment_event_id>first_event_id,payment_attempt_id>attempt_id,order_id>order_id");
        assertThat(columnCollation("payment_reconciliation_cases", "event_reference")).isEqualTo("utf8mb4_bin");
        assertThat(columnCollation("payment_reconciliation_cases", "payload_sha256")).isEqualTo("ascii_bin");
    }

    private void assertForeignKeyRoute(String table, String constraint, String expectedPairs) {
        String actualPairs = jdbc.queryForObject("SELECT GROUP_CONCAT(CONCAT(COLUMN_NAME,'>',REFERENCED_COLUMN_NAME) ORDER BY ORDINAL_POSITION SEPARATOR ',') " +
                "FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=? AND REFERENCED_TABLE_NAME IS NOT NULL",
                String.class, table, constraint);
        assertThat(actualPairs).as("foreign-key column order for %s", constraint).isEqualTo(expectedPairs);
    }

    @Test
    void v8UnknownReconciliationCaseAllowsNullableRoutingAndEnforcesIdempotentIdentity() {
        String provider = "vnpay";
        String eventReference = "unknown-" + UUID.randomUUID();
        String fingerprint = "v1:" + "a".repeat(64);
        String payload = "b".repeat(64);
        jdbc.update("INSERT INTO payment_reconciliation_cases(provider,event_reference,case_type,evidence_fingerprint,merchant_reference,amount_minor_units,provider_outcome,provider_timestamp_state,received_at,payload_sha256) VALUES (?,?,'UNKNOWN_MERCHANT_REFERENCE',?, ?,12300,'SUCCEEDED','MISSING',NOW(6),?)",
                provider, eventReference, fingerprint, eventReference, payload);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_cases WHERE provider=? AND event_reference=?", Integer.class, provider, eventReference)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment_reconciliation_cases(provider,event_reference,case_type,evidence_fingerprint,merchant_reference,amount_minor_units,provider_outcome,provider_timestamp_state,received_at,payload_sha256) VALUES (?,?,'UNKNOWN_MERCHANT_REFERENCE',?, ?,12300,'SUCCEEDED','MISSING',NOW(6),?)",
                provider, eventReference, fingerprint, eventReference, payload)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void v8SettlementEnforcesCompositeRoutesAndOneSettlementPerOrderAttemptAndEvent() {
        int userId = insertUser(unique("v8-settlement"));
        Instant created = Instant.parse("2026-10-28T03:04:05.123456Z");
        int orderId = insertOrder(userId, created);
        int otherOrderId = insertOrder(userId, created.plusSeconds(1));
        int attemptId = insertPaymentAttempt(orderId, userId, "settlement-" + UUID.randomUUID(), created);
        String eventReference = "settlement-event-" + UUID.randomUUID();
        insertPaymentEvent(attemptId, orderId, userId, eventReference, created.plusSeconds(2));
        int eventId = jdbc.queryForObject("SELECT id FROM payment_events WHERE provider='vnpay' AND event_reference=?", Integer.class, eventReference);
        LocalDateTime providerPaidAt = new PaymentUtcInstantConverter().convertToDatabaseColumn(created.plusSeconds(3));
        LocalDateTime settledAt = new PaymentUtcInstantConverter().convertToDatabaseColumn(created.plusSeconds(4));

        jdbc.update("INSERT INTO payment_settlements(order_id,payment_attempt_id,payment_event_id,provider_paid_at,settled_at) VALUES (?,?,?,?,?)",
                orderId, attemptId, eventId, providerPaidAt, settledAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE order_id=? AND payment_attempt_id=? AND payment_event_id=?", Integer.class,
                orderId, attemptId, eventId)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment_settlements(order_id,payment_attempt_id,payment_event_id,provider_paid_at,settled_at) VALUES (?,?,?,?,?)",
                orderId, attemptId, eventId, providerPaidAt, settledAt)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO payment_settlements(order_id,payment_attempt_id,payment_event_id,provider_paid_at,settled_at) VALUES (?,?,?,?,?)",
                otherOrderId, attemptId, eventId, providerPaidAt, settledAt)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void foreignKeysAndCartAndOrderChecksAreEnforced() {
        int userId = insertUser(unique("fk"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO carts(user_id, created_at, updated_at) VALUES (?, NOW(6), NOW(6))", -userId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        int cartId = insertCart(userId);
        assertThat(jdbc.queryForObject("SELECT revision FROM carts WHERE id=?", Long.class, cartId)).isZero();
        assertCheckConstraintViolation(() -> jdbc.update("UPDATE carts SET revision=-1 WHERE id=?", cartId),
                "ck_carts_revision_nonnegative");
        assertThatThrownBy(() -> insertCartItem(Integer.MAX_VALUE, "PRODUCT", 11, 1))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertCheckConstraintViolation(() -> insertCartItem(cartId, "COURSE", 9, 2), "ck_cart_items_values");
        assertCheckConstraintViolation(() -> insertCartItem(cartId, "PRODUCT", 10, 0), "ck_cart_items_values");

        int orderId = insertOrder(userId, Instant.parse("2026-10-27T03:04:05.123456Z"));
        assertCheckConstraintViolation(() -> jdbc.update("INSERT INTO order_items(order_id, line_position, item_type, item_id, name_snapshot, quantity, unit_price_vnd, voucher_eligible, gross_amount_vnd, allocated_discount_vnd, net_amount_vnd) VALUES (?, 0, 'COURSE', 99, 'fixture', 2, 100, TRUE, 200, 0, 200)", orderId), "ck_order_items_values");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cartRevisionTracksCommittedMutationsNoopsRollbackAndConcurrentWrites() throws Exception {
        int userId = insertUser(unique("cart-revision"));
        try {
            CartCatalog<Integer, Integer> testCatalog = (owner, item) -> { };
            var service = new CartApplicationService(carts, cartItems, () -> userId, testCatalog, transactionManager);

            assertThat(service.load().revision()).isZero();
            assertThat(service.add(CartItemType.PRODUCT, 101, 1).revision()).isEqualTo(1L);
            Integer productLineId = jdbc.queryForObject(
                    "SELECT id FROM cart_items WHERE cart_id=(SELECT id FROM carts WHERE user_id=?) AND item_type='PRODUCT' AND item_id=101",
                    Integer.class, userId);
            assertThat(service.add(CartItemType.COURSE, 202, 1).revision()).isEqualTo(2L);
            assertThat(service.add(CartItemType.COURSE, 202, 1).revision()).isEqualTo(2L);
            assertThat(service.update(productLineId, 1).revision()).isEqualTo(2L);
            assertThat(service.update(productLineId, 2).revision()).isEqualTo(3L);
            assertThat(service.load().revision()).isEqualTo(3L);

            int concurrentAdds = 4;
            try (var pool = Executors.newFixedThreadPool(concurrentAdds)) {
                var writes = java.util.stream.IntStream.range(0, concurrentAdds).mapToObj(index ->
                        CompletableFuture.runAsync(() -> service.add(CartItemType.PRODUCT, 2100 + index, 1), pool))
                        .toList();
                CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
            }
            assertThat(service.load().revision()).isEqualTo(3L + concurrentAdds);

            jdbc.update("UPDATE carts SET revision=? WHERE user_id=?", Long.MAX_VALUE, userId);
            assertThatThrownBy(() -> service.add(CartItemType.PRODUCT, 9999, 1))
                    .isInstanceOf(ArithmeticException.class);
            assertThat(jdbc.queryForObject("SELECT revision FROM carts WHERE user_id=?", Long.class, userId))
                    .isEqualTo(Long.MAX_VALUE);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id=(SELECT id FROM carts WHERE user_id=?) AND item_id=9999",
                    Integer.class, userId)).isZero();
            assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items WHERE id=?", Integer.class, productLineId))
                    .isEqualTo(2);
        } finally {
            deleteCartRevisionFixture(userId);
        }
    }

    @Test
    void emailUniquenessIsCaseInsensitiveAndAccentSensitive() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        insertUser("Case" + suffix + "@example.test");
        assertThatThrownBy(() -> insertUser("case" + suffix + "@example.test"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        String accent = UUID.randomUUID().toString().replace("-", "");
        insertUser("cafe" + accent + "@example.test");
        insertUser("caf\u00e9" + accent + "@example.test");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email LIKE ?", Integer.class, "%" + accent + "@example.test")).isEqualTo(2);
    }

    @Test
    void voucherAndPaymentTechnicalReferencesUseExactCaseComparison() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        insertVoucher("Voucher-" + suffix);
        insertVoucher("voucher-" + suffix);
        assertThatThrownBy(() -> insertVoucher("Voucher-" + suffix))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        int userId = insertUser(unique("payment"));
        Instant created = Instant.parse("2026-10-27T03:04:05Z");
        int orderId = insertOrder(userId, created);
        insertPaymentAttempt(orderId, userId, "Merchant-" + suffix, created);
        insertPaymentAttempt(orderId, userId, "merchant-" + suffix, created);
        assertThatThrownBy(() -> insertPaymentAttempt(orderId, userId, "Merchant-" + suffix, created))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        int attemptId = jdbc.queryForObject("SELECT id FROM payment_attempts WHERE order_id=? ORDER BY id LIMIT 1", Integer.class, orderId);
        insertPaymentEvent(attemptId, orderId, userId, "Event-" + suffix, created);
        insertPaymentEvent(attemptId, orderId, userId, "event-" + suffix, created);
        assertThatThrownBy(() -> insertPaymentEvent(attemptId, orderId, userId, "Event-" + suffix, created))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void commerceInstantMappingsRoundTripThroughDatetimeSixUnderVietnamJvmTimezone() {
        assertThat(TimeZoneCheck.currentId()).isEqualTo("Asia/Ho_Chi_Minh");
        Instant original = Instant.parse("2026-10-27T03:04:05.123456Z");
        int userId = insertUser(unique("utc"));
        Order order = Order.create(userId,
                List.of(new OrderLineSnapshot(OrderItemType.COURSE, 41, "UTC fixture", 1, 100, true)),
                0, Optional.empty(), Clock.fixed(original, ZoneOffset.UTC));
        OrderJpaEntity orderRow = OrderJpaEntity.fromSnapshot(order);
        entityManager.persist(orderRow);
        entityManager.flush();

        Voucher voucher = new Voucher("utc-" + UUID.randomUUID(), OrderVoucher.Kind.FIXED_VND, 10,
                OptionalLong.empty(), true, original, original.plusSeconds(3600), 0,
                OptionalLong.empty(), OptionalLong.empty());
        VoucherJpaEntity voucherRow = VoucherJpaEntity.fromDefinition(voucher);
        entityManager.persist(voucherRow);

        PaymentAttempt attempt = PaymentAttempt.create(orderRow.getId(), order, "vnpay", "utc-" + UUID.randomUUID(),
                Clock.fixed(original, ZoneOffset.UTC));
        PaymentAttemptJpaEntity attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
        entityManager.persist(attemptRow);
        CartJpaEntity cartRow = new CartJpaEntity(userId);
        entityManager.persist(cartRow);
        entityManager.flush();

        assertRawUtc("orders", "created_at", "id", orderRow.getId(), original);
        assertRawUtc("vouchers", "valid_from", "id", voucherRow.getId(), original);
        assertRawUtc("payment_attempts", "order_created_at", "id", attemptRow.getId(), original);
        Instant cartCreated = cartRow.getCreatedAt();
        assertThat(cartCreated.getNano() % 1_000).isZero();
        assertRawUtc("carts", "created_at", "id", cartRow.getId(), cartCreated);

        entityManager.clear();
        assertThat(entityManager.find(OrderJpaEntity.class, orderRow.getId()).getCreatedAt()).isEqualTo(original);
        assertThat(entityManager.find(VoucherJpaEntity.class, voucherRow.getId()).getValidFrom()).isEqualTo(original);
        assertThat(entityManager.find(PaymentAttemptJpaEntity.class, attemptRow.getId()).getOrderCreatedAt()).isEqualTo(original);
        assertThat(entityManager.find(CartJpaEntity.class, cartRow.getId()).getCreatedAt()).isEqualTo(cartCreated);
    }

    private void assertColumn(String table, String column, int jdbcType, Integer scale) throws Exception {
        try (var connection = jdbc.getDataSource().getConnection();
             var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            assertThat(columns.next()).as("%s.%s exists", table, column).isTrue();
            assertThat(columns.getInt("DATA_TYPE")).as("%s.%s JDBC type", table, column).isEqualTo(jdbcType);
            if (scale != null) {
                Integer precision = jdbc.queryForObject("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", Integer.class, table, column);
                assertThat(precision).as("%s.%s DATETIME fractional precision from INFORMATION_SCHEMA", table, column).isEqualTo(scale);
            }
        }
    }

    private void assertCheckConstraintViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable invalidInsert,
                                                String constraintName) {
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(invalidInsert);
        assertThat(failure != null).as("invalid insert must be rejected by %s", constraintName).isTrue();
        java.sql.SQLException sqlFailure = findSqlException(failure);
        assertThat(sqlFailure != null).as("failure must retain MySQL SQLException details").isTrue();
        assertThat(sqlFailure.getErrorCode()).as("MySQL CHECK error code").isEqualTo(3819);
        assertThat(sqlFailure.getSQLState()).as("MySQL CHECK SQLState").isEqualTo("HY000");
        assertThat(sqlFailure.getMessage()).contains(constraintName);
    }

    private java.sql.SQLException findSqlException(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sqlException) return sqlException;
        }
        return null;
    }

    private String columnCollation(String table, String column) {
        return jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", String.class, table, column);
    }

    private void assertRawUtc(String table, String column, String keyColumn, int id, Instant expected) {
        LocalDateTime stored = jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE " + keyColumn + "=?", LocalDateTime.class, id);
        assertThat(stored).isEqualTo(LocalDateTime.ofInstant(expected, ZoneOffset.UTC));
    }

    private int insertUser(String email) {
        return generated("INSERT INTO users(full_name, email, password_hash, role, is_active, created_at, updated_at) VALUES (?, ?, ?, 'USER', TRUE, NOW(6), NOW(6))", "fixture", email, "test-only-not-a-real-hash");
    }

    private int insertCart(int userId) {
        return generated("INSERT INTO carts(user_id, created_at, updated_at) VALUES (?, NOW(6), NOW(6))", userId);
    }

    private void deleteCartRevisionFixture(int userId) {
        jdbc.update("DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE user_id=?)", userId);
        jdbc.update("DELETE FROM carts WHERE user_id=?", userId);
        jdbc.update("DELETE FROM users WHERE id=?", userId);
    }

    private void insertCartItem(int cartId, String itemType, int itemId, int quantity) {
        jdbc.update("INSERT INTO cart_items(cart_id, item_type, item_id, quantity) VALUES (?, ?, ?, ?)", cartId, itemType, itemId, quantity);
    }

    private int insertOrder(int userId, Instant created) {
        var converter = new OrderUtcInstantConverter();
        LocalDateTime local = converter.convertToDatabaseColumn(created);
        return generated("INSERT INTO orders(version,user_id,currency,status,payment_status,merchandise_subtotal_vnd,eligible_subtotal_vnd,discount_vnd,shipping_fee_vnd,total_vnd,payment_expires_at,created_at,updated_at) VALUES (0,?,'VND','PENDING_PAYMENT','CREATED',100,100,0,0,100,?,?,?)", userId, local.plusMinutes(30), local, local);
    }

    private int insertVoucher(String code) {
        LocalDateTime now = new VoucherUtcInstantConverter().convertToDatabaseColumn(Instant.parse("2026-10-27T03:04:05.123456Z"));
        return generated("INSERT INTO vouchers(version,definition_version,code,kind,discount_value,active,valid_from,valid_until,minimum_subtotal_vnd,reserved_count,redeemed_count,created_at,updated_at) VALUES (0,1,?,'FIXED_VND',100,TRUE,?, ?,0,0,0,?,?)", code, now, now.plusDays(1), now, now);
    }

    private int insertPaymentAttempt(int orderId, int userId, String merchant, Instant created) {
        LocalDateTime local = new PaymentUtcInstantConverter().convertToDatabaseColumn(created);
        return generated("INSERT INTO payment_attempts(version,order_id,user_id,provider,merchant_reference,expected_amount_vnd,currency,order_created_at,expires_at,created_at,updated_at,status,review_reason) VALUES (0,?,?,'vnpay',?,100,'VND',?,?,?,?,'CREATED','NONE')", orderId, userId, merchant, local, local.plusMinutes(30), local, local);
    }

    private void insertPaymentEvent(int attemptId, int orderId, int userId, String eventReference, Instant occurredAt) {
        LocalDateTime local = new PaymentUtcInstantConverter().convertToDatabaseColumn(occurredAt);
        jdbc.update("INSERT INTO payment_events(attempt_id,order_id,user_id,provider,event_reference,merchant_reference,amount_vnd,currency,outcome,occurred_at,received_at,payload_sha256,verification_reference,fingerprint,before_status,after_status,disposition,reason,observed_order_status) VALUES (?, ?, ?, 'vnpay', ?, 'fixture-merchant', 100, 'VND', 'PENDING', ?, ?, ?, 'fixture-verified', ?, 'CREATED', 'CREATED', 'APPLIED', 'NONE', 'PENDING_PAYMENT')",
                attemptId, orderId, userId, eventReference, local, local, "a".repeat(64), "sha256:" + UUID.randomUUID());
    }

    private int generated(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        return holder.getKey().intValue();
    }

    private static String unique(String prefix) { return prefix + "-" + UUID.randomUUID() + "@example.test"; }

    private static final class TimeZoneCheck {
        private static String currentId() { return java.util.TimeZone.getDefault().getID(); }
    }

    public static final class TargetGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            ConfigurableEnvironment env = context.getEnvironment();
            String configured = env.getProperty("spring.datasource.url", "");
            boolean safe = Boolean.getBoolean("mysql.it.enabled")
                    && "drawing_course_it".equals(System.getenv("MYSQL_IT_DATABASE_ACK"))
                    && EXPECTED_URL.equals(System.getenv("MYSQL_IT_URL"))
                    && EXPECTED_URL.equals(configured)
                    && (env.getProperty("spring.flyway.url") == null || EXPECTED_URL.equals(env.getProperty("spring.flyway.url")))
                    && env.acceptsProfiles(org.springframework.core.env.Profiles.of("mysql-it"))
                    && nonBlank(System.getenv("MYSQL_IT_USERNAME"))
                    && System.getenv("MYSQL_IT_PASSWORD") != null
                    && java.util.Objects.equals(env.getProperty("spring.datasource.username"), System.getenv("MYSQL_IT_USERNAME"))
                    && java.util.Objects.equals(env.getProperty("spring.datasource.password"), System.getenv("MYSQL_IT_PASSWORD"))
                    && "com.mysql.cj.jdbc.Driver".equals(env.getProperty("spring.datasource.driver-class-name"))
                    && env.getProperty("spring.jpa.hibernate.ddl-auto", "").equalsIgnoreCase("validate")
                    && env.getProperty("spring.flyway.enabled", Boolean.class, false)
                    && env.getProperty("spring.flyway.validate-on-migrate", Boolean.class, false)
                    && env.getProperty("spring.flyway.clean-disabled", Boolean.class, false);
            if (!safe) throw new IllegalStateException("MySQL integration tests require the explicitly acknowledged isolated drawing_course_it target; no connection was opened.");
        }
        private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    }
}
