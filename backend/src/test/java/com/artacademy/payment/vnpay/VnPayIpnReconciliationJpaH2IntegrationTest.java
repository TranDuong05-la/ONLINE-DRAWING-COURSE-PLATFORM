package com.artacademy.payment.vnpay;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.repository.PaymentEventRepository;
import com.drawingcourse.backend.repository.PaymentEventConflictRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.payment.vnpay.enabled=true",
        "app.payment.vnpay.tmn-code=DEMOV210",
        "app.payment.vnpay.secret-key=ipn-test-secret",
        "app.payment.vnpay.return-url=https://merchant.example.test/return",
        "app.payment.vnpay.ipn-url=https://merchant.example.test/ipn"
})
@ActiveProfiles("test")
@Import(VnPayIpnReconciliationJpaH2IntegrationTest.TestClockConfiguration.class)
class VnPayIpnReconciliationJpaH2IntegrationTest {
    private static final Instant BASE = Instant.parse("2026-10-28T10:00:00Z");
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final AtomicInteger TRANSACTION_IDS = new AtomicInteger(78123450);

    @Autowired private VnPayIpnReconciliationService reconciliation;
    @Autowired private VnPayCallbackVerifier verifier;
    @Autowired private PaymentSessionApplicationService sessions;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentEventRepository events;
    @Autowired private PaymentEventConflictRepository conflicts;
    @Autowired private OrderRepository orders;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TestClock testClock;
    private final OrderPersistenceMapper orderMapper = new OrderPersistenceMapper();

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean @Primary TestClock ipnTestClock() { return new TestClock(); }
    }

    static class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(BASE);
        void set(Instant instant) { now.set(instant.truncatedTo(ChronoUnit.MICROS)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @BeforeEach void resetClock() { testClock.set(BASE.plusSeconds(10)); }

    @Test void classifiesOnTimeSuccessAndFailureWithoutChangingOrderState() {
        var success = payableFixture(9201, BASE.minusSeconds(5));
        var before = orders.findByIdAndUserId(success.orderId(), success.ownerId()).orElseThrow().getStatus();
        testClock.set(BASE.plusSeconds(20));
        var successResult = reconciliation.reconcile(callback(success, "00", "00", BASE.plusSeconds(1), null));
        assertEquals(IpnClassification.VALID_ON_TIME_SUCCESS, successResult.classification());
        assertEquals(PaymentStatus.SUCCEEDED, attempts.findByIdAndUserId(success.attemptId(), success.ownerId()).orElseThrow().getStatus());
        assertEquals(before, orders.findByIdAndUserId(success.orderId(), success.ownerId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, before);

        var failed = payableFixture(9202, BASE.minusSeconds(5));
        var failureResult = reconciliation.reconcile(callback(failed, "24", "05", BASE.plusSeconds(1), null));
        assertEquals(IpnClassification.VALID_FAILURE, failureResult.classification());
        assertEquals(PaymentStatus.FAILED, attempts.findByIdAndUserId(failed.attemptId(), failed.ownerId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findByIdAndUserId(failed.orderId(), failed.ownerId()).orElseThrow().getStatus());
    }

    @Test void duplicateCallbackIsIdempotentAndChangedFactsAreRecordedAsConflict() {
        var fixture = payableFixture(9203, BASE.minusSeconds(5));
        testClock.set(BASE.plusSeconds(20));
        var callback = callback(fixture, "00", "00", BASE.plusSeconds(1), null);
        var first = reconciliation.reconcile(callback);
        var replay = reconciliation.reconcile(callback);
        assertEquals(IpnClassification.VALID_ON_TIME_SUCCESS, first.classification());
        assertEquals(IpnClassification.DUPLICATE_CALLBACK, replay.classification());
        assertTrue(replay.duplicate());
        assertEquals(first.eventId(), replay.eventId());
        assertEquals(1, events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.ownerId()).size());

        var conflicting = callback(fixture, "24", "05", BASE.plusSeconds(2), null);
        var conflict = reconciliation.reconcile(conflicting);
        assertEquals(IpnClassification.CONFLICTING_CALLBACK, conflict.classification());
        assertNotNull(conflict.conflictId());
        assertEquals(PaymentStatus.SUCCEEDED,
                attempts.findByIdAndUserId(fixture.attemptId(), fixture.ownerId()).orElseThrow().getStatus());
        assertEquals(PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS,
                attempts.findByIdAndUserId(fixture.attemptId(), fixture.ownerId()).orElseThrow().getReviewReason());
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findByIdAndUserId(fixture.orderId(), fixture.ownerId()).orElseThrow().getStatus());

        var failedFirst = payableFixture(9212, BASE.minusSeconds(5));
        var failure = callback(failedFirst, "24", "05", BASE.plusSeconds(1), null);
        reconciliation.reconcile(failure);
        var changedToSuccess = reconciliation.reconcile(callback(failedFirst, "00", "00", BASE.plusSeconds(2), null));
        assertEquals(IpnClassification.CONFLICTING_CALLBACK, changedToSuccess.classification());
        assertEquals(PaymentStatus.UNKNOWN,
                attempts.findByIdAndUserId(failedFirst.attemptId(), failedFirst.ownerId()).orElseThrow().getStatus());
        assertEquals(PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS,
                attempts.findByIdAndUserId(failedFirst.attemptId(), failedFirst.ownerId()).orElseThrow().getReviewReason());
    }

    @Test void reportsUnknownMerchantReferenceWithoutInventingAnAuditRow() {
        var unknown = callback("unknown-merchant-ref", "777000", "00", "00", BASE.plusSeconds(1), null, "99910001");
        var result = reconciliation.reconcile(unknown);
        assertEquals(IpnClassification.UNKNOWN_MERCHANT_REFERENCE, result.classification());
        assertNull(result.eventId());
        assertTrue(events.findByProviderAndEventReference("vnpay", "unknown-merchant-ref:99910001").isEmpty());
    }

    @Test void classifiesAmountMismatchAndDoesNotMutateOrder() {
        var fixture = payableFixture(9204, BASE.minusSeconds(5));
        testClock.set(BASE.plusSeconds(20));
        var result = reconciliation.reconcile(callback(fixture, "00", "00", BASE.plusSeconds(1), "999900"));
        assertEquals(IpnClassification.AMOUNT_MISMATCH, result.classification());
        assertEquals(PaymentAttempt.ReviewReason.AMOUNT_MISMATCH, result.reviewReason());
        assertEquals(PaymentStatus.CREATED, attempts.findByIdAndUserId(fixture.attemptId(), fixture.ownerId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findByIdAndUserId(fixture.orderId(), fixture.ownerId()).orElseThrow().getStatus());
    }

    @Test void classifiesExpiredOrderAndLateProviderSuccessForManualReview() {
        var expired = expiredFixture(9205);
        testClock.set(BASE.plusSeconds(20));
        var expiredResult = reconciliation.reconcile(callback(expired, "24", "05", BASE.minusSeconds(1), null));
        assertEquals(IpnClassification.EXPIRED_ORDER, expiredResult.classification());

        var late = oldPendingFixture(9206);
        var lateResult = reconciliation.reconcile(callback(late, "00", "00", BASE.minusSeconds(1), null));
        assertEquals(IpnClassification.LATE_PROVIDER_SUCCESS, lateResult.classification());
        assertEquals(PaymentAttempt.ReviewReason.LATE_SUCCESS, lateResult.reviewReason());
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findByIdAndUserId(late.orderId(), late.ownerId()).orElseThrow().getStatus());
    }

    @Test void missingInvalidAndAmbiguousStatusRequireReview() {
        var missingDate = payableFixture(9207, BASE.minusSeconds(5));
        testClock.set(BASE.plusSeconds(20));
        var missingCallback = callback(missingDate, "00", "00", null, null);
        var missing = reconciliation.reconcile(missingCallback);
        assertEquals(IpnClassification.INVALID_PROVIDER_TIMESTAMP, missing.classification());
        assertEquals(PaymentAttempt.ReviewReason.INVALID_EVENT_TIME, missing.reviewReason());
        testClock.set(BASE.plusSeconds(21));
        var missingReplay = reconciliation.reconcile(missingCallback);
        assertEquals(IpnClassification.DUPLICATE_CALLBACK, missingReplay.classification());

        var invalidDate = payableFixture(9208, BASE.minusSeconds(5));
        var invalid = reconciliation.reconcile(callback(invalidDate, "00", "00", null, null, "not-a-date"));
        assertEquals(IpnClassification.INVALID_PROVIDER_TIMESTAMP, invalid.classification());

        var ambiguous = payableFixture(9209, BASE.minusSeconds(5));
        var unknownStatus = reconciliation.reconcile(callback(ambiguous, "xx", "??", BASE.plusSeconds(1), null));
        assertEquals(IpnClassification.UNKNOWN_OR_AMBIGUOUS_PAYMENT_STATUS, unknownStatus.classification());
        assertEquals(PaymentStatus.UNKNOWN, attempts.findByIdAndUserId(ambiguous.attemptId(), ambiguous.ownerId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findByIdAndUserId(ambiguous.orderId(), ambiguous.ownerId()).orElseThrow().getStatus());
    }

    @Test void concurrentReplaysPersistOneEventAndRollbackRemovesAttemptAndEventChanges() throws Exception {
        var fixture = payableFixture(9210, BASE.minusSeconds(5));
        testClock.set(BASE.plusSeconds(20));
        var callback = callback(fixture, "00", "00", BASE.plusSeconds(1), null);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return reconciliation.reconcile(callback); });
            var two = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return reconciliation.reconcile(callback); });
            var results = List.of(one.get(12, TimeUnit.SECONDS), two.get(12, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> r.classification() == IpnClassification.VALID_ON_TIME_SUCCESS).count());
            assertEquals(1, results.stream().filter(r -> r.classification() == IpnClassification.DUPLICATE_CALLBACK).count());
            assertEquals(1, events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.ownerId()).size());
        }

        var rollbackFixture = payableFixture(9211, BASE.minusSeconds(5));
        var rollbackCallback = callback(rollbackFixture, "00", "00", BASE.plusSeconds(1), null);
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            reconciliation.reconcile(rollbackCallback);
            status.setRollbackOnly();
        });
        assertTrue(events.findAllByOrderIdAndUserId(rollbackFixture.orderId(), rollbackFixture.ownerId()).isEmpty());
        assertEquals(PaymentStatus.CREATED,
                attempts.findByIdAndUserId(rollbackFixture.attemptId(), rollbackFixture.ownerId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orders.findByIdAndUserId(rollbackFixture.orderId(), rollbackFixture.ownerId()).orElseThrow().getStatus());
    }

    private record Fixture(int ownerId, int orderId, int attemptId, String merchantReference,
                           String transactionReference, long amountVnd) { }

    private Fixture payableFixture(int owner, Instant createdAt) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            var snapshot = Order.create(owner, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 1,
                    "IPN test product", 1, 12_345, true)), 0, Optional.empty(),
                    Clock.fixed(createdAt.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
            var orderRow = OrderJpaEntity.fromSnapshot(snapshot);
            entityManager.persist(orderRow);
            entityManager.flush();
            var attempt = PaymentAttempt.create(orderRow.getId(), snapshot, "vnpay", "ipn-" + UUID.randomUUID(),
                    Clock.fixed(createdAt.plusMillis(1), ZoneOffset.UTC));
            var attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
            entityManager.persist(attemptRow);
            entityManager.flush();
            return new Fixture(owner, orderRow.getId(), attemptRow.getId(), attempt.merchantReference(), nextTransactionId(), 12_345);
        });
    }

    private Fixture oldPendingFixture(int owner) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            Instant created = BASE.minus(Duration.ofMinutes(31));
            var snapshot = Order.create(owner, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 1,
                    "Old IPN test product", 1, 12_345, true)), 0, Optional.empty(), Clock.fixed(created, ZoneOffset.UTC));
            var orderRow = OrderJpaEntity.fromSnapshot(snapshot);
            entityManager.persist(orderRow);
            entityManager.flush();
            var attempt = PaymentAttempt.create(orderRow.getId(), snapshot, "vnpay", "ipn-" + UUID.randomUUID(),
                    Clock.fixed(created.plusMillis(1), ZoneOffset.UTC));
            var attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
            entityManager.persist(attemptRow);
            entityManager.flush();
            return new Fixture(owner, orderRow.getId(), attemptRow.getId(), attempt.merchantReference(), nextTransactionId(), 12_345);
        });
    }

    private Fixture expiredFixture(int owner) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            Instant created = BASE.minus(Duration.ofMinutes(31));
            var snapshot = Order.create(owner, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 1,
                    "Expired IPN test product", 1, 12_345, true)), 0, Optional.empty(), Clock.fixed(created, ZoneOffset.UTC));
            var orderRow = OrderJpaEntity.fromSnapshot(snapshot);
            entityManager.persist(orderRow);
            entityManager.flush();
            var attempt = PaymentAttempt.create(orderRow.getId(), snapshot, "vnpay", "ipn-" + UUID.randomUUID(),
                    Clock.fixed(created.plusMillis(1), ZoneOffset.UTC));
            var attemptRow = PaymentAttemptJpaEntity.newSnapshot(attempt);
            entityManager.persist(attemptRow);
            entityManager.flush();
            orderRow.expireUnpaid(orderRow.getPaymentExpiresAt());
            entityManager.flush();
            return new Fixture(owner, orderRow.getId(), attemptRow.getId(), attempt.merchantReference(), nextTransactionId(), 12_345);
        });
    }

    private VerifiedVnPayCallback callback(Fixture fixture, String responseCode, String transactionStatus,
                                           Instant payAt, String amountOverride) {
        return callback(fixture, responseCode, transactionStatus, payAt, amountOverride, null);
    }

    private VerifiedVnPayCallback callback(Fixture fixture, String responseCode, String transactionStatus,
                                           Instant payAt, String amountOverride, String dateOverride) {
        return callback(fixture.merchantReference(), amountOverride == null ? Long.toString(fixture.amountVnd() * 100)
                : amountOverride, responseCode, transactionStatus, payAt, dateOverride, fixture.transactionReference());
    }

    private VerifiedVnPayCallback callback(String merchantReference, String amountMinor, String responseCode,
                                           String transactionStatus, Instant payAt, String dateOverride) {
        return callback(merchantReference, amountMinor, responseCode, transactionStatus, payAt, dateOverride,
                nextTransactionId());
    }

    private VerifiedVnPayCallback callback(String merchantReference, String amountMinor, String responseCode,
                                           String transactionStatus, Instant payAt, String dateOverride,
                                           String transactionReference) {
        var parameters = new TreeMap<String, String>();
        parameters.put("vnp_TmnCode", "DEMOV210");
        parameters.put("vnp_Amount", amountMinor);
        parameters.put("vnp_BankCode", "NCB");
        parameters.put("vnp_OrderInfo", "IPN integration test");
        parameters.put("vnp_TransactionNo", transactionReference);
        parameters.put("vnp_ResponseCode", responseCode);
        parameters.put("vnp_TransactionStatus", transactionStatus);
        parameters.put("vnp_TxnRef", merchantReference);
        if (dateOverride != null) parameters.put("vnp_PayDate", dateOverride);
        else if (payAt != null) parameters.put("vnp_PayDate", PAY_DATE.format(payAt));
        String canonical = VnPaySignature.canonical(parameters);
        parameters.put("vnp_SecureHash", hmac(canonical));
        var multi = new LinkedHashMap<String, List<String>>();
        parameters.forEach((key, value) -> multi.put(key, List.of(value)));
        return verifier.verify(multi);
    }

    private static String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec("ipn-test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static String nextTransactionId() { return Integer.toString(TRANSACTION_IDS.incrementAndGet()); }
}
