package com.artacademy.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.exception.DuplicateResourceException;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.dto.AdminVoucherStatusRequest;
import com.drawingcourse.backend.dto.AdminVoucherUpdateRequest;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderJpaEntity;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.entity.Voucher;
import com.drawingcourse.backend.entity.VoucherClaim;
import com.drawingcourse.backend.entity.VoucherClaimJpaEntity;
import com.drawingcourse.backend.entity.VoucherJpaEntity;
import com.drawingcourse.backend.repository.VoucherClaimRepository;
import com.drawingcourse.backend.repository.VoucherRepository;
import com.drawingcourse.backend.service.AdminVoucherService;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.StoredOrder;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.checkout.VoucherReservationPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Guarded MySQL 8 verification for D-Voucher-3B. Never run without the mysql-it target guard. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mysql-it"})
@ContextConfiguration(initializers = MySqlSchemaCompatibilityIT.TargetGuard.class)
@TestPropertySource(properties = "app.voucher.admin-api-enabled=true")
@Transactional
class MySqlAdminVoucherCompatibilityIT {
    private static final Instant NOW = Instant.parse("2026-10-31T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired CustomUserDetailsService userDetails;
    @Autowired JwtService jwt;
    @Autowired VoucherRepository vouchers;
    @Autowired VoucherClaimRepository claims;
    @Autowired JpaVoucherReservationService reservations;
    @Autowired AdminVoucherService admin;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void requireGuardedV1ThroughV9Target() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("drawing_course_it");
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE "
                        + "AND version IN ('1','2','3','4','5','6','7','8','9')",
                Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=TRUE",
                Long.class)).isEqualTo(9L);
    }

    @Test
    void adminCrudExactCasePaginationVersioningAndIsolationMatchMysql() throws Exception {
        String token = bearer(createUser(Role.ADMIN));
        long voucherCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM vouchers", Long.class);
        VoucherJpaEntity control = persist(definition("CONTROL-" + shortId()));
        VoucherFingerprint controlBefore = fingerprint(control.getId());
        String upperCode = "MYSQL-ADMIN-" + shortId().toUpperCase();
        String lowerCode = upperCode.toLowerCase();

        int upperId = createdId(create(token, createBody(upperCode)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.definitionVersion").value(1)).andReturn().getResponse().getContentAsByteArray());
        int lowerId = createdId(create(token, createBody(lowerCode)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(vouchers.findByCode(upperCode)).isPresent();
        assertThat(vouchers.findByCode(lowerCode)).isPresent();

        mvc.perform(put("/api/admin/vouchers/{id}", upperId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(updateBody(1, 2_000))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.definitionVersion").value(2))
                .andExpect(jsonPath("$.data.discountValue").value(2_000));
        mvc.perform(put("/api/admin/vouchers/{id}", upperId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(updateBody(1, 3_000))))
                .andExpect(status().isConflict());

        Instant updatedBeforeNoOp = vouchers.findById(upperId).orElseThrow().getUpdatedAt();
        mvc.perform(patch("/api/admin/vouchers/{id}/status", upperId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("active", true, "expectedDefinitionVersion", 2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.definitionVersion").value(2));
        em.flush();
        em.clear();
        assertThat(vouchers.findById(upperId).orElseThrow().getUpdatedAt()).isEqualTo(updatedBeforeNoOp);
        mvc.perform(patch("/api/admin/vouchers/{id}/status", upperId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("active", false, "expectedDefinitionVersion", 2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.definitionVersion").value(3));
        mvc.perform(patch("/api/admin/vouchers/{id}/status", upperId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("active", true, "expectedDefinitionVersion", 2))))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/admin/vouchers/{id}", upperId).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value(upperCode));

        LocalDateTime tie = LocalDateTime.of(2099, 1, 1, 0, 0);
        jdbc.update("UPDATE vouchers SET created_at=? WHERE id IN (?,?)", tie, upperId, lowerId);
        mvc.perform(get("/api/admin/vouchers").header("Authorization", token).param("page", "0").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(voucherCountBefore + 3))
                .andExpect(jsonPath("$.data.content[0].id").value(Math.max(upperId, lowerId)))
                .andExpect(jsonPath("$.data.content[1].id").value(Math.min(upperId, lowerId)));
        assertThat(fingerprint(control.getId())).isEqualTo(controlBefore);
        // Keep the expected uniqueness violation last: a participating transaction can be marked rollback-only.
        create(token, createBody(upperCode)).andExpect(status().isConflict());
    }

    @Test
    void everyClaimStateBlocksFinancialEditsAndReservedClaimSurvivesDeactivation() throws Exception {
        String token = bearer(createUser(Role.ADMIN));
        for (VoucherClaim.State state : VoucherClaim.State.values()) {
            ClaimFixture fixture = claimFixture(state);
            mvc.perform(put("/api/admin/vouchers/{id}", fixture.voucherId).header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(updateBody(1, 2_000))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Voucher financial terms are immutable after the first claim"));
            if (state == VoucherClaim.State.RESERVED) {
                ClaimFingerprint before = claimFingerprint(fixture.orderId);
                mvc.perform(patch("/api/admin/vouchers/{id}/status", fixture.voucherId)
                                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(Map.of("active", false,
                                        "expectedDefinitionVersion", 1))))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.definitionVersion").value(2));
                assertThat(claimFingerprint(fixture.orderId)).isEqualTo(before);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reservationRowLockWinsBeforeAdminUpdateAndPreservesSnapshot() throws Exception {
        ReservationFixture fixture = reservationFixture();
        CountDownLatch reservationHasLock = new CountDownLatch(1);
        CountDownLatch releaseReservation = new CountDownLatch(1);
        CountDownLatch adminAttempting = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> workers = new ArrayList<>(2);
        try {
            Future<String> reservation = pool.submit(() -> inTxResult(() -> {
                VoucherReservationPort.Quote quote = reservations.quoteAndLock(fixture.userId,
                        fixture.code, 10_000, NOW).orElseThrow();
                reservationHasLock.countDown();
                await(releaseReservation);
                reservations.reserveForOrder(fixture.userId, fixture.storedOrder, fixture.order, quote, NOW);
                return "reserved";
            }));
            workers.add(reservation);
            assertThat(reservationHasLock.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> update = pool.submit(() -> {
                adminAttempting.countDown();
                try {
                    admin.update(fixture.voucherId, updateRequest(1, 2_000));
                    return "updated";
                } catch (DuplicateResourceException conflict) {
                    return conflict.getMessage();
                }
            });
            workers.add(update);
            assertThat(adminAttempting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> update.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class)
                    .as("admin update must wait for the reservation's voucher-row lock");
            releaseReservation.countDown();
            assertThat(reservation.get(10, TimeUnit.SECONDS)).isEqualTo("reserved");
            assertThat(update.get(10, TimeUnit.SECONDS))
                    .isEqualTo("Voucher financial terms are immutable after the first claim");
            inTx(() -> {
                VoucherJpaEntity row = vouchers.findByIdForUpdate(fixture.voucherId).orElseThrow();
                VoucherClaimJpaEntity claim = claims.findByOrderIdAndUserId(fixture.orderId, fixture.userId)
                        .orElseThrow();
                assertThat(row.getDefinitionVersion()).isEqualTo(1);
                assertThat(row.getValue()).isEqualTo(1_000);
                assertThat(row.getReservedCount()).isEqualTo(1);
                assertThat(claim.getState()).isEqualTo(VoucherClaim.State.RESERVED);
                assertThat(claim.getValueSnapshot()).isEqualTo(1_000);
                assertThat(claim.getDiscountVnd()).isEqualTo(1_000);
            });
        } finally {
            releaseReservation.countDown();
            WorkerStopResult stopped = stopWorkers(pool, workers);
            requireWorkersStopped(stopped, "reservation versus admin update; fixture cleanup was skipped");
            deleteReservationFixture(fixture);
            requireNoWorkerFailures(workers, "reservation versus admin update");
            requireTerminationWasNotInterrupted(stopped, "reservation versus admin update");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentStatusUpdatesWithOneVersionCannotOverwriteEachOther() throws Exception {
        Integer voucherId = inTxResult(() -> persist(definition("MYSQL-STATUS-" + shortId())).getId());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> workers = new ArrayList<>(2);
        try {
            Future<String> first = pool.submit(() -> statusResult(voucherId, start));
            workers.add(first);
            Future<String> second = pool.submit(() -> statusResult(voucherId, start));
            workers.add(second);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("changed", "stale");
            inTx(() -> {
                VoucherJpaEntity row = vouchers.findByIdForUpdate(voucherId).orElseThrow();
                assertThat(row.isActive()).isFalse();
                assertThat(row.getDefinitionVersion()).isEqualTo(2);
            });
        } finally {
            start.countDown();
            WorkerStopResult stopped = stopWorkers(pool, workers);
            requireWorkersStopped(stopped, "concurrent admin status updates; fixture cleanup was skipped");
            inTx(() -> jdbc.update("DELETE FROM vouchers WHERE id=?", voucherId));
            requireNoWorkerFailures(workers, "concurrent admin status updates");
            requireTerminationWasNotInterrupted(stopped, "concurrent admin status updates");
        }
    }

    private static WorkerStopResult stopWorkers(ExecutorService pool, List<Future<?>> workers) {
        if (pool.isTerminated()) return new WorkerStopResult(true, false);
        for (Future<?> worker : workers) {
            if (!worker.isDone()) worker.cancel(true);
        }
        pool.shutdown();
        boolean interrupted = false;
        boolean terminated = false;
        try {
            terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
            if (!terminated) {
                pool.shutdownNow();
                terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interruption) {
            interrupted = true;
            pool.shutdownNow();
            // Clear temporarily so the second bounded wait can establish whether cleanup is safe.
            Thread.interrupted();
            try {
                terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException repeated) {
                interrupted = true;
                terminated = pool.isTerminated();
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        return new WorkerStopResult(terminated || pool.isTerminated(), interrupted);
    }

    private static void requireWorkersStopped(WorkerStopResult result, String scenario) {
        if (!result.terminated()) {
            throw new AssertionError("Workers did not terminate within the bounded deadline for " + scenario
                    + "; fixture cleanup is unsafe and was not attempted");
        }
    }

    private static void requireTerminationWasNotInterrupted(WorkerStopResult result, String scenario) {
        if (result.interrupted()) {
            throw new AssertionError("Interrupted while terminating workers for " + scenario
                    + "; workers terminated and fixtures were cleaned, but the test cannot continue safely");
        }
    }

    private static void requireNoWorkerFailures(List<Future<?>> workers, String scenario) {
        for (Future<?> worker : workers) {
            if (!worker.isDone() || worker.isCancelled()) continue;
            try {
                worker.get(0, TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.ExecutionException failed) {
                throw new AssertionError("Worker failed during " + scenario, failed.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while collecting worker outcome for " + scenario, interrupted);
            } catch (TimeoutException impossibleAfterTermination) {
                throw new AssertionError("Terminated worker had no bounded outcome for " + scenario,
                        impossibleAfterTermination);
            }
        }
    }

    private org.springframework.test.web.servlet.ResultActions create(String token, Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/admin/vouchers").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private Map<String, Object> createBody(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("discountType", "FIXED_VND");
        body.put("discountValue", 1_000L);
        body.put("active", true);
        body.put("validFrom", NOW.minusSeconds(60).toString());
        body.put("validUntil", NOW.plusSeconds(3_600).toString());
        body.put("minimumEligibleMerchandiseSpendVnd", 0L);
        body.put("totalUsageLimit", 10L);
        body.put("perUserUsageLimit", 2L);
        return body;
    }

    private Map<String, Object> updateBody(long version, long value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedDefinitionVersion", version);
        body.put("discountType", "FIXED_VND");
        body.put("discountValue", value);
        body.put("maximumDiscountVnd", null);
        body.put("validFrom", NOW.minusSeconds(60).toString());
        body.put("validUntil", NOW.plusSeconds(3_600).toString());
        body.put("minimumEligibleMerchandiseSpendVnd", 0L);
        body.put("totalUsageLimit", 10L);
        body.put("perUserUsageLimit", 2L);
        return body;
    }

    private AdminVoucherUpdateRequest updateRequest(long version, long value) {
        return new AdminVoucherUpdateRequest(version, OrderVoucher.Kind.FIXED_VND, value, null,
                NOW.minusSeconds(60), NOW.plusSeconds(3_600), 0L, 10L, 2L, null, null);
    }

    private Voucher definition(String code) {
        return new Voucher(code, OrderVoucher.Kind.FIXED_VND, 1_000, OptionalLong.empty(), true,
                NOW.minusSeconds(60), NOW.plusSeconds(3_600), 0, OptionalLong.of(10), OptionalLong.of(2));
    }

    private VoucherJpaEntity persist(Voucher definition) {
        VoucherJpaEntity row = VoucherJpaEntity.fromDefinition(definition);
        em.persist(row);
        em.flush();
        return row;
    }

    private ClaimFixture claimFixture(VoucherClaim.State state) {
        User user = createUser(Role.USER);
        VoucherJpaEntity row = persist(definition("MYSQL-CLAIM-" + state + "-" + shortId()));
        Voucher definition = definition(row.getCode());
        Order order = Order.create(user.getId(), List.of(new OrderLineSnapshot(
                OrderItemType.COURSE, 701, "Claim course", 1, 10_000, true)), 0,
                Optional.of(definition.toOrderSnapshot()), CLOCK);
        OrderJpaEntity orderRow = OrderJpaEntity.fromSnapshot(order);
        em.persist(orderRow);
        em.flush();
        StoredOrder stored = new OrderPersistenceMapper().toStored(orderRow);
        VoucherReservationPort.Quote quote = reservations.quoteAndLock(user.getId(), row.getCode(), 10_000, NOW)
                .orElseThrow();
        reservations.reserveForOrder(user.getId(), stored, order, quote, NOW);
        if (state == VoucherClaim.State.REDEEMED) {
            jdbc.update("UPDATE orders SET status='CONFIRMED',payment_status='SUCCEEDED' WHERE id=?", orderRow.getId());
            em.clear();
            reservations.consumeForOrder(user.getId(), orderRow.getId(), "mysql-payment-" + shortId(),
                    NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.plusSeconds(3));
        } else if (state == VoucherClaim.State.RELEASED) {
            jdbc.update("UPDATE orders SET status='CANCELLED',payment_status='CANCELLED' WHERE id=?", orderRow.getId());
            em.clear();
            reservations.releaseForOrder(user.getId(), orderRow.getId(), VoucherClaim.ReleaseReason.CANCELLED,
                    NOW.plusSeconds(1));
        }
        em.flush();
        return new ClaimFixture(row.getId(), orderRow.getId());
    }

    private ReservationFixture reservationFixture() {
        return inTxResult(() -> {
            User user = createUser(Role.USER);
            Voucher definition = definition("MYSQL-RACE-" + shortId());
            VoucherJpaEntity voucher = persist(definition);
            Order order = Order.create(user.getId(), List.of(new OrderLineSnapshot(
                    OrderItemType.COURSE, 701, "MySQL course", 1, 10_000, true)), 0,
                    Optional.of(definition.toOrderSnapshot()), CLOCK);
            OrderJpaEntity orderRow = OrderJpaEntity.fromSnapshot(order);
            em.persist(orderRow);
            em.flush();
            return new ReservationFixture(user.getId(), voucher.getId(), orderRow.getId(), definition.code(),
                    order, new OrderPersistenceMapper().toStored(orderRow));
        });
    }

    private String statusResult(Integer voucherId, CountDownLatch start) {
        await(start);
        try {
            admin.updateStatus(voucherId, new AdminVoucherStatusRequest(false, 1L));
            return "changed";
        } catch (DuplicateResourceException stale) {
            return "stale";
        }
    }

    private void deleteReservationFixture(ReservationFixture fixture) {
        inTx(() -> {
            jdbc.update("DELETE FROM voucher_claims WHERE order_id=?", fixture.orderId);
            jdbc.update("DELETE FROM order_items WHERE order_id=?", fixture.orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", fixture.orderId);
            jdbc.update("DELETE FROM voucher_user_usage WHERE voucher_id=? AND user_id=?",
                    fixture.voucherId, fixture.userId);
            jdbc.update("DELETE FROM vouchers WHERE id=?", fixture.voucherId);
            jdbc.update("DELETE FROM users WHERE id=?", fixture.userId);
        });
    }

    private VoucherFingerprint fingerprint(Integer id) {
        return jdbc.queryForObject("SELECT code,discount_value,active,definition_version,reserved_count,redeemed_count "
                        + "FROM vouchers WHERE id=?",
                (rs, ignored) -> new VoucherFingerprint(rs.getString(1), rs.getLong(2), rs.getBoolean(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6)), id);
    }

    private ClaimFingerprint claimFingerprint(Integer orderId) {
        return jdbc.queryForObject("SELECT state,definition_version,value_snapshot,discount_vnd,payment_reference,"
                        + "release_reason FROM voucher_claims WHERE order_id=?",
                (rs, ignored) -> new ClaimFingerprint(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getString(5), rs.getString(6)), orderId);
    }

    private int createdId(byte[] response) throws Exception {
        JsonNode root = json.readTree(response);
        return root.path("data").path("id").asInt();
    }

    private User createUser(Role role) {
        return users.saveAndFlush(User.builder().fullName("MySQL Admin Voucher IT")
                .email("mysql-admin-" + shortId() + "@example.test").passwordHash("test-only")
                .role(role).isActive(true).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private void inTx(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private <T> T inTxResult(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("coordination timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record ClaimFixture(Integer voucherId, Integer orderId) { }
    private record ReservationFixture(Integer userId, Integer voucherId, Integer orderId, String code,
                                      Order order, StoredOrder storedOrder) { }
    private record VoucherFingerprint(String code, long value, boolean active, long definitionVersion,
                                      long reserved, long redeemed) { }
    private record ClaimFingerprint(String state, long definitionVersion, long value, long discount,
                                    String paymentReference, String releaseReason) { }
    private record WorkerStopResult(boolean terminated, boolean interrupted) { }
}
