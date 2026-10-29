package com.artacademy.payment.vnpay;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.repository.PaymentEventRepository;
import com.drawingcourse.backend.repository.PaymentEventConflictRepository;
import com.drawingcourse.backend.repository.PaymentSettlementRepository;
import com.drawingcourse.backend.repository.PaymentReconciliationCaseRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.expiration.CoursePurchaseReleasePort;
import com.drawingcourse.backend.service.expiration.InventoryReservationReleasePort;
import com.drawingcourse.backend.service.expiration.OrderExpirationCoordinator;
import com.drawingcourse.backend.service.expiration.ExpirationResult;
import com.drawingcourse.backend.service.expiration.VoucherExpirationReleasePort;
import com.drawingcourse.backend.service.settlement.CourseOwnershipConfirmationPort;
import com.drawingcourse.backend.service.settlement.InventoryReservationConfirmationPort;
import com.drawingcourse.backend.service.settlement.SettlementCoordinator;
import com.drawingcourse.backend.service.settlement.SettlementLedgerPort;
import com.drawingcourse.backend.service.settlement.SettlementResult;
import com.drawingcourse.backend.service.settlement.VoucherConsumptionPort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.payment.vnpay.enabled=true",
        "app.payment.vnpay.tmn-code=DEMOV210",
        "app.payment.vnpay.secret-key=ipn-test-secret",
        "app.payment.vnpay.return-url=https://merchant.example.test/return",
        "app.payment.vnpay.ipn-url=https://merchant.example.test/ipn",
        "app.payment.vnpay.ipn.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
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
    @Autowired private PaymentSettlementRepository settlements;
    @Autowired private PaymentReconciliationCaseRepository cases;
    @Autowired private JpaVoucherReservationService voucherReservations;
    @Autowired private OrderRepository orders;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VnPayProperties vnPayProperties;
    @Autowired private TestClock testClock;
    @Autowired private MockMvc mvc;
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

    @Test void publicIpnRouteVerifiesAndDurablyRecordsWithoutSettlingOrder() throws Exception {
        var fixture = payableFixture(9280, BASE.minusSeconds(5));
        var verified = callback(fixture, "00", "00", BASE.plusSeconds(1), null);
        mvc.perform(ipnRequest(verified))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith("application/json"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("00"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.Message").value("Callback recorded"));
        assertEquals(1, events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.ownerId()).size());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orders.findByIdAndUserId(fixture.orderId(), fixture.ownerId()).orElseThrow().getStatus());
        assertTrue(settlements.findByOrderId(fixture.orderId()).isEmpty());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/vnpay/ipn/extra"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/payments/vnpay/ipn"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test void publicIpnBoundaryRejectsInvalidDuplicateAndMissingSignedFieldsWithoutLeakingThem() throws Exception {
        var fixture = payableFixture(9281, BASE.minusSeconds(5));
        var valid = callback(fixture, "00", "00", BASE.plusSeconds(1), null);
        var tampered = ipnRequestWithChangedAmount(valid, "999999999");
        mvc.perform(tampered)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("97"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(valid.signature()))));
        mvc.perform(ipnRequest(valid).param("vnp_ResponseCode", "24"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("99"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/vnpay/ipn")
                        .param("vnp_TxnRef", fixture.merchantReference()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("99"));
        assertTrue(events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.ownerId()).isEmpty());
    }

    @Test void durableUnknownCallbackAndReplayReceiveAcknowledgementsWithoutClaimingSettlement() throws Exception {
        String ref = "http-unknown-" + UUID.randomUUID();
        var callback = callback(ref, "777000", "00", "00", BASE.plusSeconds(1), null, "99980001");
        mvc.perform(ipnRequest(callback))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("00"));
        mvc.perform(ipnRequest(callback))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("00"));
        assertEquals(1, countCases(ref + ":99980001", "UNKNOWN_MERCHANT_REFERENCE"));
    }

    @Test void conflictingVerifiedCallbackIsAuditedAndAcknowledgedWithoutSettling() throws Exception {
        var fixture = payableFixture(9282, BASE.minusSeconds(5));
        var original = callback(fixture, "00", "00", BASE.plusSeconds(1), null);
        var changed = callback(fixture, "24", "02", BASE.plusSeconds(2), null);
        mvc.perform(ipnRequest(original))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("00"));
        mvc.perform(ipnRequest(changed))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.RspCode").value("00"));
        assertEquals(1, events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.ownerId()).size());
        assertEquals(1, countCases(fixture.merchantReference() + ":" + fixture.transactionReference(),
                "CONFLICTING_CALLBACK"));
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orders.findByIdAndUserId(fixture.orderId(), fixture.ownerId()).orElseThrow().getStatus());
        assertTrue(settlements.findByOrderId(fixture.orderId()).isEmpty());
    }

    private static MockHttpServletRequestBuilder ipnRequest(VerifiedVnPayCallback callback) {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/vnpay/ipn");
        callback.signedParameters().forEach((key, value) -> request.param(key, value));
        request.param("vnp_SecureHash", callback.signature());
        return request;
    }

    private static MockHttpServletRequestBuilder ipnRequestWithChangedAmount(VerifiedVnPayCallback callback, String amount) {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/vnpay/ipn");
        callback.signedParameters().forEach((key, value) -> request.param(key,
                "vnp_Amount".equals(key) ? amount : value));
        request.param("vnp_SecureHash", callback.signature());
        return request;
    }

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
        var failureResult = reconciliation.reconcile(callback(failed, "24", "02", BASE.plusSeconds(1), null));
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
        assertEquals(1, countCases(fixture.merchantReference() + ":" + fixture.transactionReference(),
                "CONFLICTING_CALLBACK"));
        assertEquals(PaymentStatus.SUCCEEDED,
                attempts.findByIdAndUserId(fixture.attemptId(), fixture.ownerId()).orElseThrow().getStatus());
        assertEquals(PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS,
                attempts.findByIdAndUserId(fixture.attemptId(), fixture.ownerId()).orElseThrow().getReviewReason());
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findByIdAndUserId(fixture.orderId(), fixture.ownerId()).orElseThrow().getStatus());

        var failedFirst = payableFixture(9212, BASE.minusSeconds(5));
        var failure = callback(failedFirst, "24", "02", BASE.plusSeconds(1), null);
        reconciliation.reconcile(failure);
        var changedToSuccess = reconciliation.reconcile(callback(failedFirst, "00", "00", BASE.plusSeconds(2), null));
        assertEquals(IpnClassification.CONFLICTING_CALLBACK, changedToSuccess.classification());
        assertEquals(PaymentStatus.UNKNOWN,
                attempts.findByIdAndUserId(failedFirst.attemptId(), failedFirst.ownerId()).orElseThrow().getStatus());
        assertEquals(PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS,
                attempts.findByIdAndUserId(failedFirst.attemptId(), failedFirst.ownerId()).orElseThrow().getReviewReason());
    }

    @Test void unknownMerchantReferenceIsPersistedUnlinkedAndIdenticalReplayIsIdempotent() {
        var unknown = callback("unknown-merchant-ref", "777000", "00", "00", BASE.plusSeconds(1), null, "99910001");
        var result = reconciliation.reconcile(unknown);
        assertEquals(IpnClassification.UNKNOWN_MERCHANT_REFERENCE, result.classification());
        assertNull(result.eventId());
        assertTrue(events.findByProviderAndEventReference("vnpay", "unknown-merchant-ref:99910001").isEmpty());
        var reference = "unknown-merchant-ref:99910001";
        var firstCase = cases.findByProviderAndEventReferenceAndCaseTypeAndEvidenceFingerprint(
                "vnpay", reference, "UNKNOWN_MERCHANT_REFERENCE", "v1:" + digest(unknown)).orElseThrow();
        assertNull(firstCase.getOrderId());
        assertNull(firstCase.getPaymentAttemptId());
        assertNull(firstCase.getPaymentEventId());
        assertEquals(IpnClassification.UNKNOWN_MERCHANT_REFERENCE, reconciliation.reconcile(unknown).classification());
        assertEquals(1, countCases(reference, "UNKNOWN_MERCHANT_REFERENCE"));

        var changedEvidence = callback("unknown-merchant-ref", "777100", "00", "00", BASE.plusSeconds(2), null,
                "99910001");
        reconciliation.reconcile(changedEvidence);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_cases WHERE provider=? AND event_reference=? AND case_type=?",
                Integer.class, "vnpay", reference, "UNKNOWN_MERCHANT_REFERENCE"));
    }

    @Test void suspiciousAndUnknownStatusCallbacksCreateDurableReviewCases() {
        var suspicious = payableFixture(9216, BASE.minusSeconds(5));
        var suspiciousCallback = callback(suspicious, "07", "07", BASE.plusSeconds(1), null);
        assertEquals(IpnClassification.SUSPICIOUS_PAYMENT_REQUIRES_REVIEW,
                reconciliation.reconcile(suspiciousCallback).classification());
        assertEquals(1, countCases(suspicious.merchantReference() + ":" + suspicious.transactionReference(),
                "UNKNOWN_PAYMENT_STATUS"));

        var unknown = payableFixture(9217, BASE.minusSeconds(5));
        var unknownCallback = callback(unknown, "98", "02", BASE.plusSeconds(1), null);
        assertEquals(IpnClassification.UNKNOWN_OR_AMBIGUOUS_PAYMENT_STATUS,
                reconciliation.reconcile(unknownCallback).classification());
        assertEquals(1, countCases(unknown.merchantReference() + ":" + unknown.transactionReference(),
                "UNKNOWN_PAYMENT_STATUS"));
    }

    @Test void lateAndAmountMismatchAreDurablyLinkedToTheirPaymentEvent() {
        var late = oldPendingFixture(9218);
        var lateResult = reconciliation.reconcile(callback(late, "00", "00", BASE.minusSeconds(1), null));
        assertEquals(IpnClassification.LATE_PROVIDER_SUCCESS, lateResult.classification());
        assertEquals(1, countCases(late.merchantReference() + ":" + late.transactionReference(), "LATE_SUCCESS"));

        var amount = payableFixture(9219, BASE.minusSeconds(5));
        var amountResult = reconciliation.reconcile(callback(amount, "00", "00", BASE.plusSeconds(1), "999900"));
        assertEquals(IpnClassification.AMOUNT_MISMATCH, amountResult.classification());
        assertEquals(1, countCases(amount.merchantReference() + ":" + amount.transactionReference(), "AMOUNT_MISMATCH"));
    }

    @Test void malformedProviderTimestampIsRecordedAsInvalidWithoutInventingAValue() {
        var fixture = payableFixture(9220, BASE.minusSeconds(5));
        var result = reconciliation.reconcile(callback(fixture, "00", "00", null, null, "not-a-date"));
        assertEquals(IpnClassification.INVALID_PROVIDER_TIMESTAMP, result.classification());
        var audit = events.findById(result.eventId()).orElseThrow();
        assertTrue(audit.getVerificationReference().endsWith("paydate=invalid"));
        assertEquals(1, countCases(fixture.merchantReference() + ":" + fixture.transactionReference(),
                "INVALID_PROVIDER_TIMESTAMP"));
        var stored = cases.findByProviderAndEventReferenceAndCaseTypeAndEvidenceFingerprint("vnpay",
                fixture.merchantReference() + ":" + fixture.transactionReference(),
                "INVALID_PROVIDER_TIMESTAMP", audit.getFingerprint()).orElseThrow();
        assertNull(stored.getProviderTimestampState());
        assertNull(stored.getProviderPaidAt());
    }

    @Test void normalOnTimeSuccessCreatesNoReviewCase() {
        var fixture = payableFixture(9221, BASE.minusSeconds(5));
        var result = reconciliation.reconcile(callback(fixture, "00", "00", BASE.plusSeconds(1), null));
        assertEquals(IpnClassification.VALID_ON_TIME_SUCCESS, result.classification());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_cases WHERE order_id=?", Integer.class,
                fixture.orderId()));
    }

    @Test void unusableKnownTransactionReferenceUsesTheV8UnusableRouteWithoutCreatingAnEvent() {
        var fixture = payableFixture(9223, BASE.minusSeconds(5));
        var result = reconciliation.reconcile(callback(fixture.merchantReference(), "1234500", "07", "07",
                BASE.plusSeconds(1), null, "not-numeric"));
        assertEquals(IpnClassification.UNUSABLE_TRANSACTION_REFERENCE, result.classification());
        assertNull(result.eventId());
        var eventReference = fixture.merchantReference() + ":unusable:" + sha256("not-numeric");
        assertEquals(1, countCases(eventReference,
                "UNUSABLE_TRANSACTION_REFERENCE"));
        var caseRow = jdbc.queryForObject("SELECT provider_timestamp_state FROM payment_reconciliation_cases WHERE provider=? AND event_reference=? AND case_type=?",
                String.class, "vnpay", eventReference, "UNUSABLE_TRANSACTION_REFERENCE");
        assertEquals("VALID", caseRow);
        var attempt = attempts.findByIdAndUserId(fixture.attemptId(), fixture.ownerId()).orElseThrow();
        assertEquals(PaymentStatus.UNKNOWN, attempt.getStatus());
        assertEquals(0, events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.ownerId()).size());
    }

    @Test void casePersistenceFailureRollsBackEventAndAttemptDecision() {
        var fixture = payableFixture(9222, BASE.minusSeconds(5));
        var failingCases = mock(com.drawingcourse.backend.repository.PaymentReconciliationCaseRepository.class);
        when(failingCases.findByProviderAndEventReferenceAndCaseTypeAndEvidenceFingerprint(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(failingCases.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("injected case write failure"));
        var failingService = new VnPayIpnReconciliationService(vnPayProperties, attempts, events, conflicts,
                failingCases, orders, verifier, testClock, transactionManager);

        assertThrows(DataIntegrityViolationException.class,
                () -> failingService.reconcile(callback(fixture, "07", "07", BASE.plusSeconds(1), null)));
        assertTrue(events.findAllByOrderIdAndUserId(fixture.orderId(), fixture.ownerId()).isEmpty());
        assertEquals(PaymentStatus.CREATED,
                attempts.findByIdAndUserId(fixture.attemptId(), fixture.ownerId()).orElseThrow().getStatus());
    }

    @Test void concurrentUnknownReferenceReplayPersistsOneCase() throws Exception {
        var callback = callback("concurrent-unknown-" + UUID.randomUUID(), "777000", "00", "00", BASE.plusSeconds(1), null,
                "99910002");
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return reconciliation.reconcile(callback); });
            var second = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return reconciliation.reconcile(callback); });
            assertEquals(IpnClassification.UNKNOWN_MERCHANT_REFERENCE, first.get(12, TimeUnit.SECONDS).classification());
            assertEquals(IpnClassification.UNKNOWN_MERCHANT_REFERENCE, second.get(12, TimeUnit.SECONDS).classification());
        }
        assertEquals(1, countCases(callback.merchantReference() + ":" + callback.transactionReference(),
                "UNKNOWN_MERCHANT_REFERENCE"));
    }

    @Test void invalidSignatureCannotCreateAReconciliationCase() {
        var valid = callback("invalid-signature-ref", "100", "07", "07", BASE.plusSeconds(1), null, "123456");
        var tampered = new TreeMap<>(valid.signedParameters());
        tampered.put("vnp_Amount", "200");
        var forged = new VerifiedVnPayCallback(tampered, valid.signature());
        assertThrows(IllegalArgumentException.class, () -> reconciliation.reconcile(forged));
        assertEquals(0, countCases("invalid-signature-ref:123456", "UNKNOWN_MERCHANT_REFERENCE"));
    }

    @Test void v8RouteCheckRejectsAnUnlinkedKnownCase() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO payment_reconciliation_cases(provider,event_reference,case_type,evidence_fingerprint,
                    received_at,case_status,merchant_reference,amount_minor_units,provider_outcome,
                    provider_timestamp_state,payload_sha256)
                VALUES ('vnpay','bad-route','UNKNOWN_MERCHANT_REFERENCE','v1:%s',CURRENT_TIMESTAMP,'OPEN',
                    'ref',100,'UNKNOWN',NULL,'%s')
                """.formatted("0".repeat(64), "0".repeat(64))));
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
        assertEquals(1, countCases(expired.merchantReference() + ":" + expired.transactionReference(),
                "ORDER_NOT_PAYABLE"));

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

    @Test void suspiciousAndUnsupportedProviderOutcomesRemainUnknownAndReplaySafely() {
        var suspicious = payableFixture(9213, BASE.minusSeconds(5));
        testClock.set(BASE.plusSeconds(20));
        var suspiciousCallback = callback(suspicious, "07", "07", BASE.plusSeconds(1), null);
        var first = reconciliation.reconcile(suspiciousCallback);
        assertEquals(IpnClassification.SUSPICIOUS_PAYMENT_REQUIRES_REVIEW, first.classification());
        assertEquals(PaymentStatus.UNKNOWN,
                attempts.findByIdAndUserId(suspicious.attemptId(), suspicious.ownerId()).orElseThrow().getStatus());
        var audit = events.findById(first.eventId()).orElseThrow();
        assertEquals(PaymentEvent.Outcome.UNKNOWN, audit.getOutcome());
        assertEquals(PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED, audit.getDisposition());
        assertEquals(PaymentAttempt.ReviewReason.NONE, audit.getReason());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orders.findByIdAndUserId(suspicious.orderId(), suspicious.ownerId()).orElseThrow().getStatus());

        var unknownOutcomeConflict = reconciliation.reconcile(
                callback(suspicious, "00", "00", BASE.plusSeconds(2), null));
        assertEquals(IpnClassification.CONFLICTING_CALLBACK, unknownOutcomeConflict.classification());
        assertNotNull(unknownOutcomeConflict.conflictId());
        assertEquals(PaymentStatus.UNKNOWN,
                attempts.findByIdAndUserId(suspicious.attemptId(), suspicious.ownerId()).orElseThrow().getStatus());

        var replay = reconciliation.reconcile(suspiciousCallback);
        assertEquals(IpnClassification.DUPLICATE_CALLBACK, replay.classification());
        assertTrue(replay.duplicate());
        assertEquals(first.eventId(), replay.eventId());
        assertEquals(1, events.findAllByOrderIdAndUserId(suspicious.orderId(), suspicious.ownerId()).size());

        var contradictory = payableFixture(9214, BASE.minusSeconds(5));
        var contradictoryResult = reconciliation.reconcile(
                callback(contradictory, "00", "02", BASE.plusSeconds(1), null));
        assertEquals(IpnClassification.UNKNOWN_OR_AMBIGUOUS_PAYMENT_STATUS, contradictoryResult.classification());
        assertEquals(PaymentEvent.Outcome.UNKNOWN,
                events.findById(contradictoryResult.eventId()).orElseThrow().getOutcome());
        assertEquals(PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,
                events.findById(contradictoryResult.eventId()).orElseThrow().getDisposition());
    }

    @Test void suspiciousDebitSuccessPreventsAutomaticExpirationRelease() {
        var fixture = payableFixture(9215, BASE.minusSeconds(5));
        testClock.set(BASE.plusSeconds(20));
        var result = reconciliation.reconcile(callback(fixture, "07", "07", BASE.plusSeconds(1), null));
        assertEquals(IpnClassification.SUSPICIOUS_PAYMENT_REQUIRES_REVIEW, result.classification());

        var ledger = mock(SettlementLedgerPort.class);
        var courses = mock(CourseOwnershipConfirmationPort.class);
        var inventory = mock(InventoryReservationConfirmationPort.class);
        var vouchers = mock(VoucherConsumptionPort.class);
        var settlement = new SettlementCoordinator(orders, attempts, events, ledger, courses, inventory,
                vouchers, Clock.fixed(BASE.plusSeconds(20), ZoneOffset.UTC), transactionManager);
        assertEquals(SettlementResult.Outcome.REQUIRES_REVIEW, settlement.settle(result).outcome());
        verify(ledger).findForUpdateByOrderId(fixture.orderId());
        verify(ledger, never()).insert(anyInt(), anyInt(), anyInt(), any(), any());
        verifyNoInteractions(courses, inventory, vouchers);

        var voucherRelease = mock(VoucherExpirationReleasePort.class);
        var inventoryRelease = mock(InventoryReservationReleasePort.class);
        var courseRelease = mock(CoursePurchaseReleasePort.class);
        var expirer = new OrderExpirationCoordinator(orders, attempts, events, conflicts, settlements,
                voucherRelease, inventoryRelease, courseRelease,
                Clock.fixed(BASE.plusSeconds(1795), ZoneOffset.UTC), transactionManager);

        var expiration = expirer.expire(fixture.orderId());

        assertEquals(ExpirationResult.Outcome.REQUIRES_REVIEW, expiration.outcome());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orders.findByIdAndUserId(fixture.orderId(), fixture.ownerId()).orElseThrow().getStatus());
        verifyNoInteractions(voucherRelease, inventoryRelease, courseRelease);
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

    private int countCases(String eventReference, String caseType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_cases WHERE provider=? AND event_reference=? AND case_type=?",
                Integer.class, "vnpay", eventReference, caseType);
    }

    private static String digest(VerifiedVnPayCallback callback) {
        try {
            var canonical = new TreeMap<>(callback.signedParameters()).entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("&"));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static String nextTransactionId() { return Integer.toString(TRANSACTION_IDS.incrementAndGet()); }
}
