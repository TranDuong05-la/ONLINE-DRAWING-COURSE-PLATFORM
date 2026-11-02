package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.enums.Role;
import com.artacademy.security.AuthenticatedUserPrincipal;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.RefundEventRepository;
import com.drawingcourse.backend.repository.RefundRequestRepository;
import com.drawingcourse.backend.repository.RefundReviewCaseRepository;
import com.drawingcourse.backend.repository.RefundUnitAllocationRepository;
import com.drawingcourse.backend.repository.RefundProviderAttemptRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.refund.RefundAdminReviewService;
import com.drawingcourse.backend.service.refund.RefundAdminReviewTransaction;
import com.drawingcourse.backend.service.refund.RefundAuditedRequestService;
import com.drawingcourse.backend.service.refund.RefundPersistenceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.drawingcourse.backend.entity.RefundPersistenceTypes.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ArtAcademyApplication.class,
        properties = "spring.datasource.url=jdbc:h2:mem:refundadminreview;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class RefundAdminReviewH2IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-02T01:02:03.123456Z");
    private static final String FINGERPRINT = "a".repeat(64);
    private static final AtomicInteger USERS = new AtomicInteger(80000);

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired RefundAdminReviewService reviews;
    @Autowired RefundAdminReviewTransaction reviewTransaction;
    @Autowired RefundAuditedRequestService auditedRequests;
    @Autowired RefundRequestRepository requests;
    @Autowired RefundUnitAllocationRepository allocations;
    @Autowired RefundEventRepository events;
    @Autowired RefundReviewCaseRepository reviewCases;
    @Autowired RefundPersistenceService persistence;
    @Autowired RefundProviderAttemptRepository providerAttempts;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void courseRequestCanBeRejectedAndAuditAndAllocationAreCommittedTogether() {
        Fixture fixture = fixture(OrderItemType.COURSE);
        var created = auditedRequests.create(command(fixture, "request-key"));
        authenticate(fixture.adminId, Role.ADMIN);

        var result = reviews.reject(created.request().getId(), "decision-1", "CUSTOMER_INELIGIBLE");

        assertFalse(result.idempotentReplay());
        assertEquals(DecisionStatus.REJECTED, result.request().getDecisionStatus());
        assertEquals(fixture.adminId, result.request().getDecidedByUserId());
        assertEquals(FinancialStatus.NOT_STARTED, result.request().getFinancialStatus());
        assertEquals(AmountReservationStatus.RELEASED, result.request().getAmountReservationStatus());
        assertEquals("ADMIN_REJECTION", result.event().getOperationType());
        assertEquals("CUSTOMER_INELIGIBLE", result.event().getEvidenceReference());
        assertEquals(List.of(UnitAllocationStatus.RELEASED), allocations.findAllByRefundRequestIdOrderByUnitPosition(
                created.request().getId()).stream().map(RefundUnitAllocationJpaEntity::getAllocationStatus).toList());
        assertEquals(0, providerAttempts.countByRefundRequestId(created.request().getId()));
    }

    @Test
    void exactReplayIsIdempotentAndChangedPayloadIsDurablyMarkedAsConflict() {
        Fixture fixture = fixture(OrderItemType.COURSE);
        var created = auditedRequests.create(command(fixture, "request-key"));
        authenticate(fixture.adminId, Role.ADMIN);
        var first = reviews.reject(created.request().getId(), "decision-replay", "REJECTED_BY_POLICY");
        var replay = reviews.reject(created.request().getId(), "decision-replay", "REJECTED_BY_POLICY");
        assertFalse(first.idempotentReplay());
        assertTrue(replay.idempotentReplay());
        assertEquals(1, events.findByRefundRequestIdAndOperationTypeAndEventKey(
                created.request().getId(), "ADMIN_REJECTION", "decision-replay").stream().count());

        assertThrows(RuntimeException.class,
                () -> reviews.reject(created.request().getId(), "decision-replay", "DIFFERENT_REASON"));
        assertEquals(1, reviewCases.findAllByRefundRequestIdOrderById(created.request().getId()).size());
    }

    @Test
    void unauthorizedActorAndConflictingTerminalDecisionDoNotChangeRequest() {
        Fixture fixture = fixture(OrderItemType.COURSE);
        var created = auditedRequests.create(command(fixture, "request-key"));
        authenticate(fixture.adminId, Role.USER);
        assertThrows(AccessDeniedException.class,
                () -> reviews.reject(created.request().getId(), "decision-unauthorized", "REASON"));
        assertEquals(DecisionStatus.REQUESTED, requests.findById(created.request().getId()).orElseThrow().getDecisionStatus());

        authenticate(fixture.adminId, Role.ADMIN);
        reviews.reject(created.request().getId(), "decision-allowed", "REASON");
        assertThrows(RefundAdminReviewService.DurableReviewConflictException.class,
                () -> reviews.reject(created.request().getId(), "different-decision", "REASON"));
        assertEquals(1, events.findAllByRefundRequestIdOrderById(created.request().getId()).size());
        assertEquals(1, reviewCases.findAllByRefundRequestIdOrderById(created.request().getId()).size());
    }

    @Test
    void productReviewDefersWithoutShipmentHoldAdapter() {
        Fixture fixture = fixture(OrderItemType.PRODUCT);
        var created = auditedRequests.create(command(fixture, "request-key"));
        authenticate(fixture.adminId, Role.ADMIN);
        assertThrows(RefundAdminReviewService.ReviewUnavailableException.class,
                () -> reviews.reject(created.request().getId(), "product-rejection", "REASON"));
        assertEquals(DecisionStatus.REQUESTED, requests.findById(created.request().getId()).orElseThrow().getDecisionStatus());
        assertEquals(0, events.findAllByRefundRequestIdOrderById(created.request().getId()).size());
    }

    @Test
    void unknownProviderOutcomeKeepsFinancialAndUnitReservations() {
        Fixture fixture = fixture(OrderItemType.COURSE);
        var created = auditedRequests.create(command(fixture, "request-key"));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                requests.findForAdminUpdate(created.request().getId()).orElseThrow().approve(fixture.adminId, NOW));
        var attempt = persistence.prepareProviderAttempt(created.request().getId(), fixture.userId,
                "test-provider", "operation-" + UUID.randomUUID(), NOW);
        persistence.startProviderAttempt(created.request().getId(), fixture.userId, attempt.getId(), NOW.plusSeconds(1));
        persistence.recordUnknown(created.request().getId(), fixture.userId, attempt.getId(), FINGERPRINT,
                "test-evidence", NOW.plusSeconds(2));
        authenticate(fixture.adminId, Role.ADMIN);

        assertThrows(RefundAdminReviewService.RefundDecisionConflictException.class,
                () -> reviews.reject(created.request().getId(), "unknown-rejection", "REASON"));
        var unchanged = requests.findById(created.request().getId()).orElseThrow();
        assertEquals(FinancialStatus.UNKNOWN, unchanged.getFinancialStatus());
        assertEquals(AmountReservationStatus.RESERVED, unchanged.getAmountReservationStatus());
        assertEquals(UnitAllocationStatus.RESERVED,
                allocations.findAllByRefundRequestIdOrderByUnitPosition(created.request().getId()).getFirst().getAllocationStatus());
        assertEquals(ProviderAttemptStatus.UNKNOWN,
                providerAttempts.findById(attempt.getId()).orElseThrow().getAttemptStatus());
    }

    @Test
    void outerRollbackRevertsDecisionAllocationAndAudit() {
        Fixture fixture = fixture(OrderItemType.COURSE);
        var created = auditedRequests.create(command(fixture, "request-key"));
        authenticate(fixture.adminId, Role.ADMIN);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String fingerprint = sha("refund-admin-rejection-v1|" + created.request().getId()
                    + "|decision-rollback|REASON|" + fixture.adminId);
            reviewTransaction.reject(created.request().getId(), fixture.adminId, "decision-rollback", "REASON",
                    fingerprint, NOW);
            status.setRollbackOnly();
        });
        assertEquals(DecisionStatus.REQUESTED, requests.findById(created.request().getId()).orElseThrow().getDecisionStatus());
        assertEquals(AmountReservationStatus.RESERVED, requests.findById(created.request().getId()).orElseThrow().getAmountReservationStatus());
        assertEquals(UnitAllocationStatus.RESERVED,
                allocations.findAllByRefundRequestIdOrderByUnitPosition(created.request().getId()).getFirst().getAllocationStatus());
        assertTrue(events.findAllByRefundRequestIdOrderById(created.request().getId()).isEmpty());
    }

    @Test
    void concurrentIdenticalReviewAttemptsProduceOneDecisionAndOneEvent() throws Exception {
        Fixture fixture = fixture(OrderItemType.COURSE);
        var created = auditedRequests.create(command(fixture, "request-key"));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> rejectConcurrently(fixture, created.request().getId(), ready, start));
            var second = executor.submit(() -> rejectConcurrently(fixture, created.request().getId(), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertTrue(second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(DecisionStatus.REJECTED, requests.findById(created.request().getId()).orElseThrow().getDecisionStatus());
        assertEquals(1, events.findAllByRefundRequestIdOrderById(created.request().getId()).size());
        assertEquals(1, allocations.findAllByRefundRequestIdOrderByUnitPosition(created.request().getId()).stream()
                .filter(a -> a.getAllocationStatus() == UnitAllocationStatus.RELEASED).count());
    }

    private boolean rejectConcurrently(Fixture fixture, Long requestId, CountDownLatch ready, CountDownLatch start) throws Exception {
        authenticate(fixture.adminId, Role.ADMIN);
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timed out");
        return reviews.reject(requestId, "concurrent-decision", "SAME_REASON").request().getDecisionStatus() == DecisionStatus.REJECTED;
    }

    private void authenticate(Integer userId, Role role) {
        var principal = new AuthenticatedUserPrincipal(userId, "admin" + userId + "@example.test", "unused", role, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "unused", principal.getAuthorities()));
    }

    private static String sha(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private RefundPersistenceService.CreateCommand command(Fixture f, String key) {
        return new RefundPersistenceService.CreateCommand(f.userId, f.orderId, f.itemId, key, FINGERPRINT,
                1, "REQUEST", null, false, NOW);
    }

    private Fixture fixture(OrderItemType type) {
        int userId = USERS.incrementAndGet();
        int adminId = USERS.incrementAndGet();
        return new TransactionTemplate(transactionManager).execute(status -> {
            var order = OrderJpaEntity.fromSnapshot(Order.create(userId,
                    List.of(new OrderLineSnapshot(type, userId, "Admin review fixture", 1, 1_000, true)),
                    0, Optional.empty(), Clock.fixed(NOW.minusSeconds(60), ZoneOffset.UTC)));
            em.persist(order); em.flush();
            var item = order.getItems().getFirst();
            var stored = new OrderPersistenceMapper().toDomain(order);
            var attempt = PaymentAttemptJpaEntity.newSnapshot(PaymentAttempt.create(order.getId(), stored, "vnpay",
                    "refund-review-" + UUID.randomUUID(), Clock.fixed(NOW.minusSeconds(30), ZoneOffset.UTC)));
            em.persist(attempt); em.flush();
            em.createNativeQuery("update payment_attempts set status='SUCCEEDED', transaction_reference=:txn, succeeded_at=:at, updated_at=:at where id=:id")
                    .setParameter("txn", "txn-" + UUID.randomUUID()).setParameter("at", NOW.minusSeconds(5)).setParameter("id", attempt.getId()).executeUpdate();
            em.clear();
            var event = new PaymentEvent("vnpay", "refund-review-event-" + UUID.randomUUID(), attempt.getMerchantReference(),
                    "txn-event-" + UUID.randomUUID(), stored.getTotalVnd(), "VND", PaymentEvent.Outcome.SUCCEEDED,
                    NOW.minusSeconds(6), NOW.minusSeconds(5), FINGERPRINT, "verified-test");
            var audit = new PaymentEventAudit(null, attempt.getId(), order.getId(), userId, event, event.fingerprint(),
                    PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, PaymentEventAudit.Disposition.APPLIED,
                    PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
            var paymentEvent = PaymentEventJpaEntity.newSnapshot(audit); em.persist(paymentEvent); em.flush();
            em.persist(PaymentSettlementJpaEntity.record(order.getId(), attempt.getId(), paymentEvent.getId(),
                    NOW.minusSeconds(6), NOW.minusSeconds(4)));
            em.flush();
            return new Fixture(userId, adminId, order.getId(), item.getId());
        });
    }

    private record Fixture(Integer userId, Integer adminId, Integer orderId, Integer itemId) { }
}
