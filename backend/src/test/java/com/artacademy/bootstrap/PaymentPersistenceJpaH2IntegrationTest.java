package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class PaymentPersistenceJpaH2IntegrationTest {
    private static final Instant CREATED = Instant.parse("2026-10-25T03:04:05.123456Z");
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentEventRepository events;
    @Autowired private ApplicationContext context;

    private Integer createOrder(Integer owner) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var snapshot = Order.create(owner, List.of(new OrderLineSnapshot(OrderItemType.COURSE, 8,
                    "Payment test course", 1, 12_345, true)), 0, Optional.empty(), Clock.fixed(CREATED, ZoneOffset.UTC));
            var row = OrderJpaEntity.fromSnapshot(snapshot);
            entityManager.persist(row);
            entityManager.flush();
            return row.getId();
        });
    }

    private PaymentAttemptJpaEntity createAttempt(Integer orderId, Integer owner, String merchant) {
        var order = orders.findByIdAndUserId(orderId, owner).orElseThrow();
        var snapshot = new com.drawingcourse.backend.service.OrderPersistenceMapper().toDomain(order);
        return PaymentAttemptJpaEntity.newSnapshot(PaymentAttempt.create(orderId, snapshot, "test-provider", merchant,
                Clock.fixed(CREATED.plusSeconds(3), ZoneOffset.UTC)));
    }

    @Test void attemptAndEventRoundTripPreserveOwnerMoneyDeadlineAndUtcSnapshots() {
        Integer orderId = createOrder(8101);
        Integer attemptId = new TransactionTemplate(transactionManager).execute(status -> {
            var row = createAttempt(orderId, 8101, "merchant-h2-roundtrip");
            entityManager.persist(row);
            entityManager.flush();
            return row.getId();
        });
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var row = attempts.findByIdAndUserId(attemptId, 8101).orElseThrow();
            var domain = new PaymentPersistenceMapper().toDomain(row);
            assertEquals(8101, domain.userId());
            assertEquals(12_345L, domain.expectedAmountVnd());
            assertEquals("VND", domain.currency());
            assertEquals(CREATED, domain.orderCreatedAt());
            assertEquals(CREATED.plusSeconds(1800), domain.expiresAt());
            assertEquals(CREATED.plusSeconds(3), domain.createdAt());
            var event = new PaymentEvent("test-provider", "event-h2-1", "merchant-h2-roundtrip", null,
                    12_345, "VND", PaymentEvent.Outcome.PENDING, CREATED.plusSeconds(4), CREATED.plusSeconds(5),
                    "a".repeat(64), "verified-test-reference");
            var audit = new PaymentEventAudit(null, attemptId, orderId, 8101, event, event.fingerprint(),
                    PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentEventAudit.Disposition.APPLIED,
                    PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
            var eventRow = PaymentEventJpaEntity.newSnapshot(audit);
            entityManager.persist(eventRow);
            entityManager.flush();
            entityManager.clear();
            var restored = new PaymentPersistenceMapper().toDomain(events.findByProviderAndEventReferenceAndUserId(
                    "test-provider", "event-h2-1", 8101).orElseThrow());
            assertEquals(audit.event(), restored.event());
            assertEquals(audit.fingerprint(), restored.fingerprint());
            assertEquals(audit.disposition(), restored.disposition());
        });
        assertTrue(attempts.findByIdAndUserId(attemptId, 8102).isEmpty());
    }

    @Test void uniqueMerchantAndEventIdentitiesAreEnforcedByH2() {
        Integer firstOrder = createOrder(8103);
        Integer secondOrder = createOrder(8103);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.persist(createAttempt(firstOrder, 8103, "merchant-h2-unique"));
            entityManager.flush();
        });
        assertThrows(ConstraintViolationException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.persist(createAttempt(secondOrder, 8103, "merchant-h2-unique"));
            entityManager.flush();
        }));
    }

    @Test void optimisticVersionRejectsStaleAttemptUpdate() {
        Integer orderId = createOrder(8104);
        Integer attemptId = new TransactionTemplate(transactionManager).execute(status -> {
            var row = createAttempt(orderId, 8104, "merchant-h2-version");
            entityManager.persist(row);
            entityManager.flush();
            return row.getId();
        });
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            first.getTransaction().begin();
            second.getTransaction().begin();
            var left = first.find(PaymentAttemptJpaEntity.class, attemptId);
            var right = second.find(PaymentAttemptJpaEntity.class, attemptId);
            var original = new PaymentPersistenceMapper().toDomain(left);
            var later = original.withIdentity(attemptId);
            var one = new PaymentAttempt(attemptId, later.orderId(), later.userId(), later.provider(), later.merchantReference(),
                    later.expectedAmountVnd(), later.currency(), later.orderCreatedAt(), later.expiresAt(), later.createdAt(),
                    CREATED.plusSeconds(9), PaymentStatus.PENDING, null, null, PaymentAttempt.ReviewReason.NONE);
            left.applyDecision(one);
            first.flush();
            first.getTransaction().commit();
            var stale = new PaymentAttempt(attemptId, later.orderId(), later.userId(), later.provider(), later.merchantReference(),
                    later.expectedAmountVnd(), later.currency(), later.orderCreatedAt(), later.expiresAt(), later.createdAt(),
                    CREATED.plusSeconds(10), PaymentStatus.UNKNOWN, null, null, PaymentAttempt.ReviewReason.NONE);
            right.applyDecision(stale);
            assertThrows(OptimisticLockException.class, second::flush);
        } finally {
            if (first.getTransaction().isActive()) first.getTransaction().rollback();
            if (second.getTransaction().isActive()) second.getTransaction().rollback();
            first.close();
            second.close();
        }
    }

    @Test void eventIdentityAndConflictFingerprintUniquenessAreEnforced() {
        Integer orderId = createOrder(8105);
        Integer attemptId = new TransactionTemplate(transactionManager).execute(status -> {
            var row = createAttempt(orderId, 8105, "merchant-h2-event-unique");
            entityManager.persist(row);
            entityManager.flush();
            return row.getId();
        });
        var event = new PaymentEvent("test-provider", "event-h2-unique", "merchant-h2-event-unique", null,
                12_345, "VND", PaymentEvent.Outcome.PENDING, CREATED.plusSeconds(4), CREATED.plusSeconds(5),
                "b".repeat(64), "verified-event-unique");
        Integer eventId = new TransactionTemplate(transactionManager).execute(status -> {
            var audit = new PaymentEventAudit(null, attemptId, orderId, 8105, event, event.fingerprint(),
                    PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentEventAudit.Disposition.APPLIED,
                    PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
            var row = PaymentEventJpaEntity.newSnapshot(audit);
            entityManager.persist(row);
            entityManager.flush();
            return row.getId();
        });
        var duplicateAudit = new PaymentEventAudit(null, attemptId, orderId, 8105, event, event.fingerprint(),
                PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentEventAudit.Disposition.APPLIED,
                PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
        assertThrows(ConstraintViolationException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.persist(PaymentEventJpaEntity.newSnapshot(duplicateAudit));
            entityManager.flush();
        }));

        var first = new TransactionTemplate(transactionManager).execute(status -> new PaymentPersistenceMapper().toDomain(
                events.findByProviderAndEventReferenceAndUserId("test-provider", "event-h2-unique", 8105).orElseThrow()));
        var attempt = attempts.findByIdAndUserId(attemptId, 8105).map(new PaymentPersistenceMapper()::toDomain).orElseThrow();
        var changed = new PaymentEvent("test-provider", "event-h2-unique", "merchant-h2-event-unique", null,
                12_345, "VND", PaymentEvent.Outcome.PENDING, CREATED.plusSeconds(4), CREATED.plusSeconds(5),
                "c".repeat(64), "verified-event-conflict");
        var conflict = PaymentEventConflictJpaEntity.verifiedConflict(first, changed, attempt, OrderStatus.PENDING_PAYMENT);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.persist(conflict);
            entityManager.flush();
        });
        assertThrows(ConstraintViolationException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.persist(PaymentEventConflictJpaEntity.verifiedConflict(first, changed, attempt, OrderStatus.PENDING_PAYMENT));
            entityManager.flush();
        }));
        assertNotNull(eventId);
    }
    @Test void paymentWorkflowBeansRemainAbsent() {
        assertFalse(context.containsBean("paymentSettlementCoordinator"));
        assertFalse(context.containsBean("vnPayIpnController"));
        assertFalse(context.containsBean("paymentInitiationController"));
    }
}