package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.payment.vnpay.*;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.repository.OrderHistoryProjection;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guarded MySQL 8 integration tests for durable VNPay reconciliation cases. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles({"test", "mysql-it"})
@ContextConfiguration(initializers = MySqlSchemaCompatibilityIT.TargetGuard.class)
@Import(MySqlReconciliationCompatibilityIT.TestSupportConfiguration.class)
@TestPropertySource(properties = {
        "app.payment.vnpay.enabled=true",
        "app.payment.vnpay.tmn-code=DEMOV210",
        "app.payment.vnpay.secret-key=mysql-it-only-secret",
        "app.payment.vnpay.return-url=https://merchant.example.test/return",
        "app.payment.vnpay.ipn-url=https://merchant.example.test/ipn",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2",
        "spring.datasource.hikari.maximum-pool-size=8"
})
class MySqlReconciliationCompatibilityIT {
    private static final String PROVIDER = "vnpay";
    private static final String TMN = "DEMOV210";
    private static final String SECRET = "mysql-it-only-secret";
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentEventRepository events;
    @Autowired private PaymentEventConflictRepository conflicts;
    @Autowired private PaymentReconciliationCaseRepository cases;
    @Autowired private PaymentSettlementRepository settlements;
    @Autowired private VnPayIpnReconciliationService reconciliation;
    @Autowired private VnPayCallbackVerifier verifier;
    @Autowired private VnPayProperties properties;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TestCaseRepository testCaseRepository;
    @Autowired private TestOrderRepository testOrderRepository;

    private final List<Fixture> fixtures = new CopyOnWriteArrayList<>();
    private final List<String> unlinkedMerchantReferences = new CopyOnWriteArrayList<>();

    @BeforeEach
    void assertExactIsolatedTargetAndV1ThroughV8() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("drawing_course_it");
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IN ('1','2','3','4','5','6','7','8')", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_reconciliation_cases'", Integer.class)).isEqualTo(1);
    }

    @AfterEach
    void removeOnlyRowsCreatedByThisClass() {
        testCaseRepository.reset();
        testOrderRepository.reset();
        for (String merchantReference : unlinkedMerchantReferences)
            jdbc.update("DELETE FROM payment_reconciliation_cases WHERE provider=? AND merchant_reference=?",
                    PROVIDER, merchantReference);
        unlinkedMerchantReferences.clear();
        for (Fixture fixture : fixtures) {
            Integer orderId = fixture.orderId();
            jdbc.update("DELETE FROM payment_reconciliation_cases WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_event_conflicts WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_events WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM payment_attempts WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM order_items WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", orderId);
            jdbc.update("DELETE FROM users WHERE id=?", fixture.userId());
        }
        fixtures.clear();
    }

    @Test
    void unknownReferenceIsUnlinkedAndSameEvidenceReplayReusesOneCase() {
        String merchant = "mysql-it-unknown-" + UUID.randomUUID();
        unlinkedMerchantReferences.add(merchant);
        var callback = callback(merchant, "1234500", "00", "00", "90010001");
        assertThat(reconciliation.reconcile(callback).classification()).isEqualTo(IpnClassification.UNKNOWN_MERCHANT_REFERENCE);
        assertThat(reconciliation.reconcile(callback).classification()).isEqualTo(IpnClassification.UNKNOWN_MERCHANT_REFERENCE);
        var row = caseFor(merchant + ":90010001", "UNKNOWN_MERCHANT_REFERENCE");
        assertThat(row.getOrderId()).isNull();
        assertThat(row.getPaymentAttemptId()).isNull();
        assertThat(row.getPaymentEventId()).isNull();
        assertThat(countCases(merchant + ":90010001", "UNKNOWN_MERCHANT_REFERENCE")).isEqualTo(1);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentUnknownReferenceReplaysUseTheV8UniqueIdentity() throws Exception {
        String merchant = "mysql-it-race-unknown-" + UUID.randomUUID();
        unlinkedMerchantReferences.add(merchant);
        var callback = callback(merchant, "1234500", "07", "07", "90010002");
        synchronizeIdentityPrechecks(merchant + ":90010002", "UNKNOWN_MERCHANT_REFERENCE");
        runConcurrent(() -> reconciliation.reconcile(callback), () -> reconciliation.reconcile(callback));
        assertThat(countCases(merchant + ":90010002", "UNKNOWN_MERCHANT_REFERENCE")).isEqualTo(1);
    }

    @Test
    void concurrentChangedSignedEvidenceGetsDistinctIdentityWithoutOverwritingEitherCase() throws Exception {
        String merchant = "mysql-it-changed-" + UUID.randomUUID();
        unlinkedMerchantReferences.add(merchant);
        var first = callback(merchant, "1234500", "07", "07", "90010003");
        var changed = callback(merchant, "1234600", "07", "07", "90010003");
        synchronizeIdentityPrechecks(merchant + ":90010003", "UNKNOWN_MERCHANT_REFERENCE");
        runConcurrent(() -> reconciliation.reconcile(first), () -> reconciliation.reconcile(changed));
        assertThat(countCases(merchant + ":90010003", "UNKNOWN_MERCHANT_REFERENCE")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT evidence_fingerprint FROM payment_reconciliation_cases WHERE provider=? AND event_reference=? AND case_type=?",
                String.class, PROVIDER, merchant + ":90010003", "UNKNOWN_MERCHANT_REFERENCE"))
                .containsExactlyInAnyOrder("v1:" + payloadDigest(first), "v1:" + payloadDigest(changed));
    }

    @Test
    void knownSuspiciousCallbackIsLinkedAndConcurrentDuplicateCreatesOneEventAndCase() throws Exception {
        Fixture fixture = fixture();
        var callback = callback(fixture.merchantReference(), "1234500", "07", "07", fixture.transactionReference());
        testOrderRepository.synchronizeNextOrderLock(fixture.orderId());
        var results = runConcurrent(() -> reconciliation.reconcile(callback), () -> reconciliation.reconcile(callback));
        assertThat(results).extracting(IpnReconciliationResult::classification)
                .containsExactlyInAnyOrder(IpnClassification.SUSPICIOUS_PAYMENT_REQUIRES_REVIEW, IpnClassification.DUPLICATE_CALLBACK);
        var caseRow = caseFor(fixture.eventReference(), "UNKNOWN_PAYMENT_STATUS");
        assertThat(caseRow.getPaymentEventId()).isNotNull();
        assertThat(caseRow.getPaymentAttemptId()).isEqualTo(fixture.attemptId());
        assertThat(caseRow.getOrderId()).isEqualTo(fixture.orderId());
        assertThat(events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.userId())).hasSize(1);
        assertThat(settlements.findByOrderId(fixture.orderId())).isEmpty();
        assertThat(countCases(fixture.eventReference(), "UNKNOWN_PAYMENT_STATUS")).isEqualTo(1);
        assertThat(orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentIdenticalKnownSuccessCreatesOneEventWithoutSettlement() throws Exception {
        Fixture fixture = fixture();
        var callback = callback(fixture.merchantReference(), "1234500", "00", "00", fixture.transactionReference());
        testOrderRepository.synchronizeNextOrderLock(fixture.orderId());
        var outcomes = runConcurrent(() -> reconciliation.reconcile(callback), () -> reconciliation.reconcile(callback));
        assertThat(outcomes).extracting(IpnReconciliationResult::classification)
                .containsExactlyInAnyOrder(IpnClassification.VALID_ON_TIME_SUCCESS, IpnClassification.DUPLICATE_CALLBACK);
        assertThat(events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.userId())).hasSize(1);
        assertThat(countCases(fixture.eventReference(), "UNKNOWN_PAYMENT_STATUS")).isZero();
        assertThat(settlements.findByOrderId(fixture.orderId())).isEmpty();
        assertThat(orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentChangedKnownEvidencePreservesFirstEventAndAuditsOneConflict() throws Exception {
        Fixture fixture = fixture();
        var success = callback(fixture.merchantReference(), "1234500", "00", "00", fixture.transactionReference());
        var failure = callback(fixture.merchantReference(), "1234500", "24", "02", fixture.transactionReference());
        testOrderRepository.synchronizeNextOrderLock(fixture.orderId());
        var outcomes = runConcurrent(() -> reconciliation.reconcile(success), () -> reconciliation.reconcile(failure));
        assertThat(outcomes).extracting(IpnReconciliationResult::classification)
                .contains(IpnClassification.CONFLICTING_CALLBACK);
        assertThat(events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.userId())).hasSize(1);
        assertThat(conflicts.findAllByOrderIdAndUserId(fixture.orderId(), fixture.userId())).hasSize(1);
        assertThat(countCases(fixture.eventReference(), "CONFLICTING_CALLBACK")).isEqualTo(1);
        assertThat(settlements.findByOrderId(fixture.orderId())).isEmpty();
        assertThat(orders.findByIdAndUserId(fixture.orderId(), fixture.userId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void conflictingVerifiedCallbackPreservesOriginalPaymentEvent() {
        Fixture fixture = fixture();
        var first = callback(fixture.merchantReference(), "1234500", "00", "00", fixture.transactionReference());
        var different = callback(fixture.merchantReference(), "1234500", "24", "02", fixture.transactionReference());
        var applied = reconciliation.reconcile(first);
        var original = events.findById(applied.eventId()).orElseThrow();
        var originalFingerprint = original.getFingerprint();
        assertThat(reconciliation.reconcile(different).classification()).isEqualTo(IpnClassification.CONFLICTING_CALLBACK);
        var after = events.findById(applied.eventId()).orElseThrow();
        assertThat(after.getFingerprint()).isEqualTo(originalFingerprint);
        assertThat(after.getOutcome()).isEqualTo(PaymentEvent.Outcome.SUCCEEDED);
        assertThat(countCases(fixture.eventReference(), "CONFLICTING_CALLBACK")).isEqualTo(1);
        assertThat(conflicts.findAllByOrderIdAndUserId(fixture.orderId(), fixture.userId())).hasSize(1);
    }

    @Test
    void v8RejectsInvalidRoutesAndDatabaseForeignKeyFailureIsNotReplay() {
        assertMysqlCheckConstraintViolation(() -> jdbc.update("""
                INSERT INTO payment_reconciliation_cases(provider,event_reference,case_type,evidence_fingerprint,
                    received_at,case_status,merchant_reference,amount_minor_units,provider_outcome,
                    provider_timestamp_state,payload_sha256)
                VALUES ('vnpay','mysql-it-bad-check','UNKNOWN_MERCHANT_REFERENCE',?,NOW(6),'OPEN',
                    'bad-ref',100,'UNKNOWN',NULL,?)
                """, "v1:" + "a".repeat(64), "a".repeat(64)), "ck_payment_reconciliation_route");

        var invalidFk = PaymentReconciliationCaseJpaEntity.linked(PROVIDER, "mysql-it-bad-fk",
                "LATE_SUCCESS", "v1:" + "b".repeat(64), Integer.MAX_VALUE, null, Integer.MAX_VALUE,
                Integer.MAX_VALUE, null, Instant.now());
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> cases.saveAndFlush(invalidFk)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sequentialReplayFindsTheCanonicalCaseBeforeInsert() {
        String merchant = "mysql-it-unique-race-" + UUID.randomUUID();
        unlinkedMerchantReferences.add(merchant);
        var callback = callback(merchant, "1234500", "00", "00", "90010004");
        assertThat(reconciliation.reconcile(callback).classification()).isEqualTo(IpnClassification.UNKNOWN_MERCHANT_REFERENCE);
        assertThat(reconciliation.reconcile(callback).classification()).isEqualTo(IpnClassification.UNKNOWN_MERCHANT_REFERENCE);
        assertThat(countCases(merchant + ":90010004", "UNKNOWN_MERCHANT_REFERENCE")).isEqualTo(1);
    }

    @Test
    void uniqueRaceRetryOccursAfterRollbackAndFreshReadFindsWinner() throws Exception {
        String merchant = "mysql-it-fresh-retry-" + UUID.randomUUID();
        unlinkedMerchantReferences.add(merchant);
        var callback = callback(merchant, "1234500", "07", "07", "90010005");
        synchronizeIdentityPrechecks(merchant + ":90010005", "UNKNOWN_MERCHANT_REFERENCE");
        var outcomes = runConcurrent(() -> reconciliation.reconcile(callback), () -> reconciliation.reconcile(callback));
        assertThat(outcomes).allMatch(result -> result.classification() == IpnClassification.UNKNOWN_MERCHANT_REFERENCE);
        assertThat(countCases(merchant + ":90010005", "UNKNOWN_MERCHANT_REFERENCE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT case_status FROM payment_reconciliation_cases WHERE provider=? AND event_reference=?",
                String.class, PROVIDER, merchant + ":90010005")).isEqualTo("OPEN");
    }

    @Test
    void caseInsertFailureRollsBackPersistedAttemptAndEventChanges() {
        Fixture fixture = fixture();
        testCaseRepository.failNextWrite(new DataIntegrityViolationException("mysql-it injected case persistence failure"));
        var suspicious = callback(fixture.merchantReference(), "1234500", "07", "07", fixture.transactionReference());
        assertThatThrownBy(() -> reconciliation.reconcile(suspicious))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.userId())).isEmpty();
        assertThat(attempts.findByIdAndUserId(fixture.attemptId(), fixture.userId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CREATED);
        assertThat(countCases(fixture.eventReference(), "UNKNOWN_PAYMENT_STATUS")).isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void orderLockTimeoutIsNotReportedAsSuccessfulReconciliation() throws Exception {
        Fixture fixture = fixture();
        var callback = callback(fixture.merchantReference(), "1234500", "00", "00", fixture.transactionReference());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM orders WHERE id=? FOR UPDATE", Integer.class, fixture.orderId());
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<IpnReconciliationResult> request = pool.submit(() -> reconciliation.reconcile(callback));
            assertThatThrownBy(() -> request.get(8, TimeUnit.SECONDS)).hasCauseInstanceOf(RuntimeException.class);
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.userId())).isEmpty();
        assertThat(countCases(fixture.eventReference(), "LATE_SUCCESS")).isZero();
        assertThat(attempts.findByIdAndUserId(fixture.attemptId(), fixture.userId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CREATED);
    }

    private Fixture fixture() {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            Integer userId = users.saveAndFlush(User.builder().fullName("MySQL Reconciliation IT")
                    .email("mysql-recon-" + UUID.randomUUID() + "@example.test").passwordHash("test-only")
                    .role(Role.USER).isActive(true).build()).getId();
            Instant created = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var snapshot = Order.create(userId, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 9_991,
                    "MySQL reconciliation fixture", 1, 12_345, true)), 0, Optional.empty(), Clock.fixed(created, ZoneOffset.UTC));
            var order = OrderJpaEntity.fromSnapshot(snapshot);
            em.persist(order);
            em.flush();
            String merchant = "mysql-it-merchant-" + UUID.randomUUID();
            var attempt = PaymentAttempt.create(order.getId(), snapshot, PROVIDER, merchant,
                    Clock.fixed(created.plusMillis(1), ZoneOffset.UTC));
            var attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
            em.persist(attemptRow);
            em.flush();
            var fixture = new Fixture(userId, order.getId(), attemptRow.getId(), merchant,
                    Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 999_999_999_999_999L)));
            fixtures.add(fixture);
            return fixture;
        });
    }

    private VerifiedVnPayCallback callback(String merchant, String amountMinor, String response, String status,
                                           String transactionReference) {
        var params = new TreeMap<String, String>();
        params.put("vnp_TmnCode", TMN);
        params.put("vnp_Amount", amountMinor);
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_OrderInfo", "guarded reconciliation test");
        params.put("vnp_TransactionNo", transactionReference);
        params.put("vnp_ResponseCode", response);
        params.put("vnp_TransactionStatus", status);
        params.put("vnp_TxnRef", merchant);
        params.put("vnp_PayDate", PAY_DATE.format(Instant.now().minusSeconds(5)));
        var multiple = new LinkedHashMap<String, List<String>>();
        params.forEach((key, value) -> multiple.put(key, List.of(value)));
        multiple.put("vnp_SecureHash", List.of(hmac(canonical(params))));
        return verifier.verify(multiple);
    }

    private PaymentReconciliationCaseJpaEntity caseFor(String eventReference, String type) {
        String fingerprint = jdbc.queryForObject("SELECT evidence_fingerprint FROM payment_reconciliation_cases WHERE provider=? AND event_reference=? AND case_type=? ORDER BY id LIMIT 1",
                String.class, PROVIDER, eventReference, type);
        return cases.findByProviderAndEventReferenceAndCaseTypeAndEvidenceFingerprint(PROVIDER, eventReference, type, fingerprint).orElseThrow();
    }

    private int countCases(String eventReference, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_cases WHERE provider=? AND event_reference=? AND case_type=?",
                Integer.class, PROVIDER, eventReference, type);
    }

    private <T> List<T> runConcurrent(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<T> a = pool.submit(() -> { await(start); return first.call(); });
            Future<T> b = pool.submit(() -> { await(start); return second.call(); });
            start.countDown();
            return List.of(a.get(12, TimeUnit.SECONDS), b.get(12, TimeUnit.SECONDS));
        }
    }

    private void synchronizeIdentityPrechecks(String eventReference, String caseType) {
        testCaseRepository.synchronizeIdentityPrechecks(eventReference, caseType);
    }

    private static void assertMysqlCheckConstraintViolation(Runnable operation, String expectedConstraint) {
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(operation::run);
        assertThat(failure).as("invalid row rejected by MySQL CHECK %s", expectedConstraint).isNotNull();
        java.sql.SQLException sqlFailure = null;
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof java.sql.SQLException sql) { sqlFailure = sql; break; }
        org.junit.jupiter.api.Assertions.assertNotNull(sqlFailure);
        assertThat(sqlFailure.getErrorCode()).isEqualTo(3819);
        assertThat(sqlFailure.getSQLState()).isEqualTo("HY000");
        assertThat(sqlFailure.getMessage()).contains(expectedConstraint);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSupportConfiguration {
        @Bean TestCaseRepository testCaseRepository(
                @Qualifier("paymentReconciliationCaseRepository") PaymentReconciliationCaseRepository delegate) {
            return new TestCaseRepository(delegate);
        }

        @Bean @Primary PaymentReconciliationCaseRepository testCaseRepositoryPrimary(TestCaseRepository support) {
            return support;
        }

        @Bean TestOrderRepository testOrderRepository(@Qualifier("orderRepository") OrderRepository delegate) {
            return new TestOrderRepository(delegate);
        }

        @Bean @Primary OrderRepository testOrderRepositoryPrimary(TestOrderRepository support) { return support; }
    }

    /** Concrete delegating test adapter avoids Mockito invoking abstract Spring Data methods. */
    static final class TestCaseRepository implements PaymentReconciliationCaseRepository {
        private final PaymentReconciliationCaseRepository delegate;
        private volatile RuntimeException nextWriteFailure;
        private volatile IdentityBarrier barrier;

        TestCaseRepository(PaymentReconciliationCaseRepository delegate) { this.delegate = delegate; }

        @Override public Optional<PaymentReconciliationCaseJpaEntity> findById(Integer id) {
            return delegate.findById(id);
        }

        @Override public Optional<PaymentReconciliationCaseJpaEntity> findByProviderAndEventReferenceAndCaseTypeAndEvidenceFingerprint(
                String provider, String eventReference, String caseType, String evidenceFingerprint) {
            var result = delegate.findByProviderAndEventReferenceAndCaseTypeAndEvidenceFingerprint(
                    provider, eventReference, caseType, evidenceFingerprint);
            var currentBarrier = barrier;
            if (currentBarrier != null && currentBarrier.matches(eventReference, caseType) && result.isEmpty())
                currentBarrier.awaitBoth();
            return result;
        }

        @Override public Optional<PaymentReconciliationCaseJpaEntity> findByPaymentEventConflictId(Integer conflictId) {
            return delegate.findByPaymentEventConflictId(conflictId);
        }

        @Override public <S extends PaymentReconciliationCaseJpaEntity> S saveAndFlush(S row) {
            RuntimeException failure = nextWriteFailure;
            if (failure != null) {
                nextWriteFailure = null;
                throw failure;
            }
            return delegate.saveAndFlush(row);
        }

        void failNextWrite(RuntimeException failure) { nextWriteFailure = failure; }
        void synchronizeIdentityPrechecks(String eventReference, String caseType) {
            barrier = new IdentityBarrier(eventReference, caseType);
        }
        void reset() { nextWriteFailure = null; barrier = null; }
    }

    /** Delegates every Order repository operation and can align callbacks just before the Order lock. */
    static final class TestOrderRepository implements OrderRepository {
        private final OrderRepository delegate;
        private volatile OrderLockBarrier barrier;
        TestOrderRepository(OrderRepository delegate) { this.delegate = delegate; }

        @Override public Optional<Integer> findUserIdById(Integer id) { return delegate.findUserIdById(id); }
        @Override public Optional<OrderJpaEntity> findByIdAndUserId(Integer id, Integer userId) {
            return delegate.findByIdAndUserId(id, userId);
        }
        @Override public Optional<OrderJpaEntity> findForUpdate(Integer id, Integer userId) {
            var current = barrier;
            if (current != null && current.orderId.equals(id)) current.awaitBoth();
            return delegate.findForUpdate(id, userId);
        }
        @Override public <S extends OrderJpaEntity> S saveAndFlush(S order) { return delegate.saveAndFlush(order); }
        @Override public Page<OrderHistoryProjection> findHistoryByUserId(Integer userId, Pageable pageable) {
            return delegate.findHistoryByUserId(userId, pageable);
        }
        @Override public boolean existsActivePendingCoursePurchase(Integer userId, Integer courseId,
                OrderItemType itemType, OrderStatus orderStatus, Instant observedAt) {
            return delegate.existsActivePendingCoursePurchase(userId, courseId, itemType, orderStatus, observedAt);
        }
        void synchronizeNextOrderLock(Integer orderId) { barrier = new OrderLockBarrier(orderId); }
        void reset() { barrier = null; }
    }

    private static final class OrderLockBarrier {
        private final Integer orderId;
        private final CountDownLatch both = new CountDownLatch(2);
        private OrderLockBarrier(Integer orderId) { this.orderId = orderId; }
        private void awaitBoth() {
            both.countDown();
            try {
                if (!both.await(8, TimeUnit.SECONDS))
                    throw new IllegalStateException("Both callbacks did not reach the Order lock boundary");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    private static final class IdentityBarrier {
        private final String eventReference;
        private final String caseType;
        private final CountDownLatch bothReads = new CountDownLatch(2);
        private IdentityBarrier(String eventReference, String caseType) {
            this.eventReference = eventReference;
            this.caseType = caseType;
        }
        boolean matches(String eventReference, String caseType) {
            return this.eventReference.equals(eventReference) && this.caseType.equals(caseType);
        }
        void awaitBoth() {
            bothReads.countDown();
            try {
                if (!bothReads.await(8, TimeUnit.SECONDS))
                    throw new IllegalStateException("Both MySQL case identity prechecks did not complete");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    private static String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String canonical(Map<String, String> params) {
        return new TreeMap<>(params).entrySet().stream()
                .map(entry -> java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String payloadDigest(VerifiedVnPayCallback callback) {
        try {
            var canonical = new TreeMap<>(callback.signedParameters()).entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining("&"));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("MySQL synchronization timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private record Fixture(Integer userId, Integer orderId, Integer attemptId, String merchantReference,
                           String transactionReference) {
        String eventReference() { return merchantReference + ":" + transactionReference; }
    }
}
