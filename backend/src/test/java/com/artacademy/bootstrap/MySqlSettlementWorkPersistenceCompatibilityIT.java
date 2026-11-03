package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.PaymentSettlementWorkClaimRepository;
import com.drawingcourse.backend.repository.PaymentSettlementWorkItemRepository;
import com.drawingcourse.backend.service.settlement.SettlementWorkClaimService;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Guarded MySQL V9 migration tests. All fixture writes share the test transaction and roll back. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles({"test", "mysql-it"})
@ContextConfiguration(initializers = MySqlSchemaCompatibilityIT.TargetGuard.class)
@Transactional
class MySqlSettlementWorkPersistenceCompatibilityIT {
    private static final String DB = "drawing_course_it";
    private static final String PROVIDER = "vnpay";
    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00.123456Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SettlementWorkClaimService claimService;
    @Autowired private PaymentSettlementWorkClaimRepository claimRows;
    @Autowired private PaymentSettlementWorkItemRepository workItems;

    @BeforeEach
    void assertExactDatabaseAndV9History() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DB);
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IN ('1','2','3','4','5','6','7','8','9')", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version='9'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=TRUE", Long.class)).isEqualTo(9L);
    }

    @Test
    void v9ColumnsTypesDefaultsIndexesChecksAndBinaryCollationsMatchMigration() {
        assertColumn("payment_settlement_work_items", "id", "int", "NO", null, null, null);
        assertColumn("payment_settlement_work_items", "payment_event_id", "int", "NO", null, null, null);
        assertColumn("payment_settlement_work_items", "attempt_count", "smallint", "NO", "0", null, null);
        assertColumn("payment_settlement_work_items", "retry_generation", "bigint", "NO", "0", null, null);
        assertColumn("payment_settlement_work_actions", "id", "int", "NO", null, null, null);
        assertColumn("payment_settlement_work_actions", "work_item_id", "int", "NO", null, null, null);
        assertColumn("payment_settlement_work_actions", "actor_user_id", "int", "NO", null, null, null);
        assertColumn("payment_settlement_work_actions", "prior_attempt_count", "smallint", "NO", null, null, null);
        assertColumn("payment_settlement_work_actions", "prior_retry_generation", "bigint", "NO", null, null, null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_settlement_work_items' AND DATA_TYPE='datetime' AND DATETIME_PRECISION=6", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_settlement_work_actions' AND COLUMN_NAME='created_at'", Integer.class)).isEqualTo(6);

        assertIndex("payment_settlement_work_items", "uk_payment_settlement_work_event", "payment_event_id", true);
        assertIndex("payment_settlement_work_actions", "uk_payment_settlement_work_action_key", "work_item_id,action_key", true);
        assertIndex("payment_settlement_work_items", "ix_payment_settlement_work_due", "state,available_at,id", false);
        assertIndex("payment_settlement_work_items", "ix_payment_settlement_work_lease", "state,lease_until,id", false);
        assertIndex("payment_settlement_work_items", "ix_payment_settlement_work_review", "state,updated_at,id", false);
        assertIndex("payment_settlement_work_actions", "ix_payment_settlement_work_action_history", "work_item_id,id", false);
        assertChecks("payment_settlement_work_items", "ck_payment_settlement_work_state", "ck_payment_settlement_work_attempts", "ck_payment_settlement_work_generation", "ck_payment_settlement_work_failure", "ck_payment_settlement_work_fields");
        assertChecks("payment_settlement_work_actions", "ck_payment_settlement_work_action", "ck_payment_settlement_work_action_attempts", "ck_payment_settlement_work_action_generation");
        assertCheckDefinitionContains("payment_settlement_work_items", "ck_payment_settlement_work_state",
                "state", "ready", "retry_wait", "leased", "completed", "manual_review", "closed_not_eligible");

        assertCollation("payment_settlement_work_items", "state", "utf8mb4_bin");
        assertCollation("payment_settlement_work_items", "lease_token", "ascii_bin");
        assertCollation("payment_settlement_work_items", "lease_owner", "ascii_bin");
        assertCollation("payment_settlement_work_items", "last_failure_code", "ascii_bin");
        assertCollation("payment_settlement_work_actions", "action_key", "ascii_bin");
        assertCollation("payment_settlement_work_actions", "action_type", "utf8mb4_bin");
        assertCollation("payment_settlement_work_actions", "from_state", "utf8mb4_bin");
        assertCollation("payment_settlement_work_actions", "to_state", "utf8mb4_bin");
        assertCollation("payment_settlement_work_actions", "reason_code", "ascii_bin");

        assertFk("payment_settlement_work_items", "fk_payment_settlement_work_event", "payment_events", "RESTRICT");
        assertFk("payment_settlement_work_actions", "fk_payment_settlement_work_action_item", "payment_settlement_work_items", "RESTRICT");
        assertFk("payment_settlement_work_actions", "fk_payment_settlement_work_action_actor", "users", "RESTRICT");
    }

    @Test
    void directSqlUsesCounterDefaultsAndUniqueEventKey() {
        Fixture f = fixture();
        int workId = insertWorkWithDatabaseDefaults(f.eventId);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM payment_settlement_work_items WHERE id=?", Short.class, workId)).isZero();
        assertThat(jdbc.queryForObject("SELECT retry_generation FROM payment_settlement_work_items WHERE id=?", Long.class, workId)).isZero();

        SQLException duplicate = sqlFailure(() -> insertWorkWithDatabaseDefaults(f.eventId));
        assertThat(duplicate.getErrorCode()).isEqualTo(1062);
        assertThat(duplicate.getMessage()).contains("uk_payment_settlement_work_event");
    }

    @Test
    void workItemCheckConstraintsRejectInvalidStateCountersAndLeaseFields() {
        // An unrecognized state also fails ck_payment_settlement_work_fields: that CHECK
        // enumerates all valid states as branches. MySQL may report either failing CHECK;
        // this server reports work_fields. The state CHECK definition is inspected above.
        assertCheckViolation(() -> insertWork(fixture().eventId, "UNRECOGNIZED", 0, 0, true, null, null, null, null), "ck_payment_settlement_work_fields");
        assertCheckViolation(() -> insertWork(fixture().eventId, "READY", 9, 0, true, null, null, null, null), "ck_payment_settlement_work_attempts");
        assertCheckViolation(() -> insertWork(fixture().eventId, "READY", 0, -1, true, null, null, null, null), "ck_payment_settlement_work_generation");
        assertCheckViolation(() -> insertWork(fixture().eventId, "LEASED", 1, 0, false, null, null, null, null), "ck_payment_settlement_work_fields");

        Fixture valid = fixture();
        int validWork = insertWork(valid.eventId, "READY", 0, 0, true, null, null, null, null);
        assertCheckViolation(() -> jdbc.update("UPDATE payment_settlement_work_items SET last_failure_code='UNRECOGNIZED' WHERE id=?", validWork),
                "ck_payment_settlement_work_failure");
    }

    @Test
    void workAndActionForeignKeysRejectMissingTargets() {
        assertForeignKeyViolation(() -> insertWork(Integer.MAX_VALUE, "READY", 0, 0, true, null, null, null, null), "fk_payment_settlement_work_event");
        Fixture f = fixture();
        int validWork = insertWork(f.eventId, "MANUAL_REVIEW", 1, 0, false, null, null, null, null);
        assertForeignKeyViolation(() -> insertAction(Integer.MAX_VALUE, f.userId, UUID.randomUUID().toString()), "fk_payment_settlement_work_action_item");
        assertForeignKeyViolation(() -> insertAction(validWork, Integer.MAX_VALUE, UUID.randomUUID().toString()), "fk_payment_settlement_work_action_actor");
    }

    @Test
    void actionUniqueKeyAndTransitionCheckAreEnforced() {
        Fixture f = fixture();
        int workId = insertWork(f.eventId, "MANUAL_REVIEW", 1, 0, false, null, null, null, null);
        String key = "9a4c57f7-9199-4bcc-9a1c-30f756234fa8";
        insertAction(workId, f.userId, key);
        SQLException duplicate = sqlFailure(() -> insertAction(workId, f.userId, key));
        assertThat(duplicate.getErrorCode()).isEqualTo(1062);
        assertThat(duplicate.getMessage()).contains("uk_payment_settlement_work_action_key");

        assertCheckViolation(() -> jdbc.update("""
                INSERT INTO payment_settlement_work_actions(work_item_id,action_key,action_type,from_state,to_state,
                    actor_user_id,reason_code,prior_attempt_count,prior_retry_generation,created_at)
                VALUES (?,?, 'REQUEUE','READY','COMPLETED',?,'ADAPTER_RESTORED',0,0,NOW(6))
                """, workId, UUID.randomUUID().toString(), f.userId), "ck_payment_settlement_work_action");
        assertCheckViolation(() -> insertActionSnapshot(workId, f.userId, UUID.randomUUID().toString(), 9, 0),
                "ck_payment_settlement_work_action_attempts");
        assertCheckViolation(() -> insertActionSnapshot(workId, f.userId, UUID.randomUUID().toString(), 1, -1),
                "ck_payment_settlement_work_action_generation");
    }

    @Test
    void referencedRowsCannotBeDeletedUnderRestrictForeignKeys() {
        Fixture f = fixture();
        int workId = insertWork(f.eventId, "MANUAL_REVIEW", 1, 0, false, null, null, null, null);
        int actorId = users.saveAndFlush(User.builder().fullName("V9 action actor")
                .email("mysql-v9-actor-" + UUID.randomUUID() + "@example.test")
                .passwordHash("test-only").role(Role.USER).isActive(true).build()).getId();
        insertAction(workId, actorId, UUID.randomUUID().toString());

        assertRestrictViolation(() -> jdbc.update("DELETE FROM payment_events WHERE id=?", f.eventId),
                "fk_payment_settlement_work_event");
        assertRestrictViolation(() -> jdbc.update("DELETE FROM payment_settlement_work_items WHERE id=?", workId),
                "fk_payment_settlement_work_action_item");
        assertRestrictViolation(() -> jdbc.update("DELETE FROM users WHERE id=?", actorId),
                "fk_payment_settlement_work_action_actor");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
    void independentWorkersCannotClaimOneReadyItemTwice() throws Exception {
        Fixture f = committedFixture();
        int workId = new TransactionTemplate(transactionManager).execute(status ->
                workItemsInsert(f.eventId, claimRows.databaseUtcNow().minusSeconds(1)));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier start = new CyclicBarrier(2);
            var first = pool.submit(() -> { start.await(); return claimService.claimNext("mysql-worker-a"); });
            var second = pool.submit(() -> { start.await(); return claimService.claimNext("mysql-worker-b"); });
            var a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat((a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0)).isEqualTo(1);
            assertThat(a.orElseGet(b::orElseThrow).id()).isEqualTo(workId);
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM payment_settlement_work_items WHERE id=?",
                    Integer.class, workId)).isEqualTo(1);
        } finally {
            cleanup(f);
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
    void skipLockedClaimSkipsARowHeldByAnotherConnection() throws Exception {
        Fixture f = committedFixture();
        int workId = new TransactionTemplate(transactionManager).execute(status ->
                workItemsInsert(f.eventId, claimRows.databaseUtcNow().minusSeconds(1)));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM payment_settlement_work_items WHERE id=? FOR UPDATE", Integer.class, workId);
                locked.countDown();
                try {
                    if (!release.await(8, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Timed out waiting to release test row lock");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return null;
            }));
            try {
                assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(claimService.claimNext("skip-locked-worker")).isEmpty();
            } finally {
                release.countDown();
            }
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT state FROM payment_settlement_work_items WHERE id=?",
                    String.class, workId)).isEqualTo("READY");
        } finally {
            release.countDown();
            cleanup(f);
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
    void delayedFencedUpdateRejectsLeaseThatExpiresWhileWaitingForRowLock() throws Exception {
        Fixture f = committedFixture();
        Instant sampledBeforeWait = claimRows.databaseUtcNow();
        Instant expiresAt = sampledBeforeWait.plusSeconds(2);
        String token = UUID.randomUUID().toString();
        int workId = new TransactionTemplate(transactionManager).execute(status ->
                workItemsInsert(f.eventId, sampledBeforeWait.minusSeconds(1)));
        jdbc.update("UPDATE payment_settlement_work_items SET state='LEASED',attempt_count=1,available_at=NULL,lease_token=?,lease_owner=?,lease_until=? WHERE id=?",
                token, "fence-delay-worker", LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC), workId);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM payment_settlement_work_items WHERE id=? FOR UPDATE", Integer.class, workId);
                locked.countDown();
                awaitRelease(release);
                return null;
            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var updater = pool.submit(() -> {
                updateStarted.countDown();
                return new TransactionTemplate(transactionManager).execute(status -> claimRows.scheduleRetry(
                        workId, token, "DEADLOCK", sampledBeforeWait, sampledBeforeWait.plusSeconds(5)));
            });
            try {
                assertThat(updateStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                waitUntilDatabaseTime(expiresAt);
            } finally {
                release.countDown();
            }
            assertThat(updater.get(8, java.util.concurrent.TimeUnit.SECONDS)).isZero();
            holder.get(8, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT state FROM payment_settlement_work_items WHERE id=?",
                    String.class, workId)).isEqualTo("LEASED");
            assertThat(jdbc.queryForObject("SELECT lease_token FROM payment_settlement_work_items WHERE id=?",
                    String.class, workId)).isEqualTo(token);
        } finally {
            release.countDown();
            cleanup(f);
        }
    }

    private void waitUntilDatabaseTime(Instant deadline) throws InterruptedException {
        long end = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        while (claimRows.databaseUtcNow().isBefore(deadline)) {
            if (System.nanoTime() >= end) throw new AssertionError("MySQL UTC clock did not reach lease deadline");
            CountDownLatch pause = new CountDownLatch(1);
            pause.await(10, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(8, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out holding the test work-row lock");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private int workItemsInsert(int eventId, Instant availableAt) {
        return workItems.saveAndFlush(PaymentSettlementWorkItemJpaEntity.ready(eventId, availableAt)).getId();
    }

    private Fixture committedFixture() {
        return new TransactionTemplate(transactionManager).execute(status -> fixture());
    }

    private void cleanup(Fixture f) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Integer attemptId = jdbc.queryForObject("SELECT attempt_id FROM payment_events WHERE id=?", Integer.class, f.eventId);
            Integer orderId = jdbc.queryForObject("SELECT order_id FROM payment_events WHERE id=?", Integer.class, f.eventId);
            jdbc.update("DELETE FROM payment_settlement_work_items WHERE payment_event_id=?", f.eventId);
            jdbc.update("DELETE FROM payment_events WHERE id=?", f.eventId);
            jdbc.update("DELETE FROM payment_attempts WHERE id=?", attemptId);
            jdbc.update("DELETE FROM order_items WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", orderId);
            jdbc.update("DELETE FROM users WHERE id=?", f.userId);
        });
    }
    private Fixture fixture() {
        User user = users.saveAndFlush(User.builder().fullName("V9 MySQL fixture")
                .email("mysql-v9-" + UUID.randomUUID() + "@example.test")
                .passwordHash("test-only").role(Role.USER).isActive(true).build());
        var orderSnapshot = Order.create(user.getId(), java.util.List.of(
                new OrderLineSnapshot(OrderItemType.COURSE, 900_001, "V9 IT course", 1, 12_000, true)),
                0, java.util.Optional.empty(), Clock.fixed(BASE, ZoneOffset.UTC));
        var orderRow = OrderJpaEntity.fromSnapshot(orderSnapshot);
        em.persist(orderRow);
        em.flush();

        String merchant = "mysql-v9-" + UUID.randomUUID();
        var attempt = PaymentAttempt.create(orderRow.getId(), orderSnapshot, PROVIDER, merchant,
                Clock.fixed(BASE.plusSeconds(1), ZoneOffset.UTC));
        var attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
        em.persist(attemptRow);
        em.flush();

        String eventRef = "mysql-v9-event-" + UUID.randomUUID();
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO payment_events(attempt_id,order_id,user_id,provider,event_reference,merchant_reference,
                        transaction_reference,amount_vnd,currency,outcome,occurred_at,received_at,payload_sha256,
                        verification_reference,fingerprint,before_status,after_status,disposition,reason,observed_order_status)
                    VALUES (?,?,?,?,?,?,NULL,12000,'VND','SUCCEEDED',?,?,?,?,?,'CREATED','SUCCEEDED',
                        'RECONCILIATION_REQUIRED','NONE','PENDING_PAYMENT')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, attemptRow.getId());
            ps.setInt(2, orderRow.getId());
            ps.setInt(3, user.getId());
            ps.setString(4, PROVIDER);
            ps.setString(5, eventRef);
            ps.setString(6, merchant);
            Timestamp timestamp = Timestamp.valueOf(LocalDateTime.ofInstant(BASE.plusSeconds(2), ZoneOffset.UTC));
            ps.setTimestamp(7, timestamp);
            ps.setTimestamp(8, timestamp);
            ps.setString(9, "a".repeat(64));
            ps.setString(10, "v9-it-test");
            ps.setString(11, "v1:" + "a".repeat(64));
            return ps;
        }, keyHolder);
        Number eventKey = keyHolder.getKey();
        if (eventKey == null) throw new IllegalStateException("MySQL did not return the fixture Event ID");
        return new Fixture(user.getId(), eventKey.intValue());
    }

    private int insertWorkWithDatabaseDefaults(int eventId) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO payment_settlement_work_items(payment_event_id,state,available_at,created_at,updated_at)
                    VALUES (?,'READY',NOW(6),NOW(6),NOW(6))
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, eventId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("MySQL did not return the fixture work ID");
        return key.intValue();
    }

    private int insertWork(int eventId, String state, int attempts, long generation, boolean available,
                           String token, String owner, Timestamp leaseUntil, Timestamp completedAt) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO payment_settlement_work_items(payment_event_id,state,attempt_count,retry_generation,
                        available_at,lease_token,lease_owner,lease_until,created_at,updated_at,completed_at)
                    VALUES (?,?,?,?,?,?,?,?,NOW(6),NOW(6),?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, eventId);
            ps.setString(2, state);
            ps.setInt(3, attempts);
            ps.setLong(4, generation);
            if (available) ps.setTimestamp(5, Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
            else ps.setNull(5, java.sql.Types.TIMESTAMP);
            ps.setString(6, token);
            ps.setString(7, owner);
            ps.setTimestamp(8, leaseUntil);
            ps.setTimestamp(9, completedAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("MySQL did not return the fixture work ID");
        return key.intValue();
    }

    private void insertAction(int workId, int actorId, String key) {
        insertActionSnapshot(workId, actorId, key, 1, 0);
    }

    private void insertActionSnapshot(int workId, int actorId, String key, int attempts, long generation) {
        jdbc.update("""
                INSERT INTO payment_settlement_work_actions(work_item_id,action_key,action_type,from_state,to_state,
                    actor_user_id,reason_code,prior_attempt_count,prior_retry_generation,created_at)
                VALUES (?,?,'REQUEUE','MANUAL_REVIEW','READY',?,'ADAPTER_RESTORED',?,?,NOW(6))
                """, workId, key, actorId, attempts, generation);
    }

    private void assertColumn(String table, String column, String dataType, String nullable,
                              String defaultValue, Integer precision, String collation) {
        var rows = jdbc.queryForList("""
                SELECT DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,DATETIME_PRECISION,COLLATION_NAME
                FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?
                """, table, column);
        assertThat(rows).as(table + "." + column).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.get("DATA_TYPE").toString()).isEqualTo(dataType);
        String columnType = row.get("COLUMN_TYPE").toString().toLowerCase(java.util.Locale.ROOT);
        if (dataType.equals("int") || dataType.equals("smallint") || dataType.equals("bigint"))
            assertThat(columnType).doesNotContain("unsigned");
        assertThat(row.get("IS_NULLABLE")).isEqualTo(nullable);
        assertThat(row.get("COLUMN_DEFAULT") == null ? null : row.get("COLUMN_DEFAULT").toString()).isEqualTo(defaultValue);
        assertThat(row.get("DATETIME_PRECISION") == null ? null : ((Number) row.get("DATETIME_PRECISION")).intValue()).isEqualTo(precision);
        if (collation != null) assertThat(row.get("COLLATION_NAME")).isEqualTo(collation);
    }

    private void assertIndex(String table, String index, String columns, boolean unique) {
        String actual = jdbc.queryForObject("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')
                FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?
                """, String.class, table, index);
        assertThat(actual).isEqualTo(columns);
        Integer nonUnique = jdbc.queryForObject("""
                SELECT NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? LIMIT 1
                """, Integer.class, table, index);
        assertThat(nonUnique == 0).isEqualTo(unique);
    }

    private void assertChecks(String table, String... names) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
                    AND TABLE_NAME=? AND CONSTRAINT_TYPE='CHECK' AND CONSTRAINT_NAME IN (%s)
                """.formatted(String.join(",", java.util.Collections.nCopies(names.length, "?"))),
                Integer.class, prepend(table, names));
        assertThat(found).isEqualTo(names.length);
    }

    private void assertCheckDefinitionContains(String table, String constraint, String... fragments) {
        String definition = jdbc.queryForObject("""
                SELECT cc.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS cc
                JOIN information_schema.TABLE_CONSTRAINTS tc
                  ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME
                WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME=? AND tc.CONSTRAINT_NAME=?
                    AND tc.CONSTRAINT_TYPE='CHECK'
                """, String.class, table, constraint).toLowerCase(java.util.Locale.ROOT);
        for (String fragment : fragments)
            assertThat(definition).as("definition of " + constraint).contains(fragment.toLowerCase(java.util.Locale.ROOT));
    }

    private void assertFk(String table, String name, String target, String deleteRule) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=?
                    AND REFERENCED_TABLE_NAME=? AND DELETE_RULE=?
                """, Integer.class, table, name, target, deleteRule)).isEqualTo(1);
    }

    private void assertCollation(String table, String column, String expected) {
        assertThat(jdbc.queryForObject("""
                SELECT COLLATION_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?
                """, String.class, table, column)).isEqualTo(expected);
    }

    private void assertCheckViolation(ThrowingSql operation, String constraint) {
        SQLException ex = sqlFailure(operation);
        assertThat(ex.getErrorCode()).isEqualTo(3819);
        assertThat(ex.getSQLState()).isEqualTo("HY000");
        assertThat(ex.getMessage()).contains(constraint);
    }

    private void assertForeignKeyViolation(ThrowingSql operation, String constraint) {
        SQLException ex = sqlFailure(operation);
        assertThat(ex.getErrorCode()).isEqualTo(1452);
        assertThat(ex.getSQLState()).isEqualTo("23000");
        assertThat(ex.getMessage()).contains(constraint);
    }

    private void assertRestrictViolation(ThrowingSql operation, String constraint) {
        SQLException ex = sqlFailure(operation);
        assertThat(ex.getErrorCode()).isEqualTo(1451);
        assertThat(ex.getSQLState()).isEqualTo("23000");
        assertThat(ex.getMessage()).contains(constraint);
    }
    private SQLException sqlFailure(ThrowingSql operation) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).as("SQL statement should be rejected by MySQL").isNotNull();
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof SQLException sql) return sql;
        throw new AssertionError("Expected a SQLException cause but got " + failure.getClass().getName(), failure);
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] values = new Object[rest.length + 1];
        values[0] = first;
        System.arraycopy(rest, 0, values, 1, rest.length);
        return values;
    }

    private record Fixture(int userId, int eventId) { }
    @FunctionalInterface private interface ThrowingSql { void run() throws Exception; }
}
