package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.repository.PaymentEventRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the guarded Order state transition against the isolated in-memory H2 profile. */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class OrderConfirmationJpaH2IntegrationTest {
    private static final int USER_ID = 7301;
    private static final Instant CREATED = Instant.parse("2026-10-28T03:00:00.000000Z");
    private static final Instant PAID_AT = CREATED.plusSeconds(10);
    private static final Instant RECEIVED_AT = CREATED.plusSeconds(11);
    private static final String DIGEST = "b".repeat(64);

    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentEventRepository events;

    @Test
    void confirmationPersistsWorkflowFieldsWithoutChangingOrderSnapshots() {
        var ids = persistEvidence();
        var originalTotal = tx().execute(status -> orders.findByIdAndUserId(ids.orderId, USER_ID).orElseThrow().getTotalVnd());
        tx().executeWithoutResult(status -> confirm(ids));

        var confirmed = tx().execute(status -> orders.findByIdAndUserId(ids.orderId, USER_ID).orElseThrow());
        assertEquals(OrderStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, confirmed.getPaymentStatus());
        assertEquals(originalTotal, confirmed.getTotalVnd());
        assertEquals(CREATED.plusSeconds(30 * 60), confirmed.getPaymentExpiresAt());
    }

    @Test
    void rollbackRestoresPendingOrderAndPaymentStatus() {
        var ids = persistEvidence();
        tx().executeWithoutResult(status -> {
            confirm(ids);
            entityManager.flush();
            status.setRollbackOnly();
        });

        var restored = tx().execute(status -> orders.findByIdAndUserId(ids.orderId, USER_ID).orElseThrow());
        assertEquals(OrderStatus.PENDING_PAYMENT, restored.getStatus());
        assertEquals(PaymentStatus.CREATED, restored.getPaymentStatus());
    }

    private void confirm(Ids ids) {
        var order = orders.findForUpdate(ids.orderId, USER_ID).orElseThrow();
        var attempt = attempts.findByIdAndUserId(ids.attemptId, USER_ID).orElseThrow();
        var event = events.findById(ids.eventId).orElseThrow();
        order.confirmPayment(new PaymentPersistenceMapper().toDomain(attempt), new PaymentPersistenceMapper().toDomain(event));
    }

    private Ids persistEvidence() {
        return tx().execute(status -> {
            var orderDomain = Order.create(USER_ID,
                    List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 91, "Confirmation fixture", 1, 22_500, true)),
                    0, Optional.empty(), Clock.fixed(CREATED, ZoneOffset.UTC));
            var orderRow = OrderJpaEntity.fromSnapshot(orderDomain);
            entityManager.persist(orderRow);
            entityManager.flush();

            var createdAttempt = PaymentAttempt.create(orderRow.getId(), orderDomain, "vnpay",
                    "confirmation-merchant-" + orderRow.getId(), Clock.fixed(CREATED.plusSeconds(2), ZoneOffset.UTC));
            var succeededAttempt = new PaymentAttempt(null, createdAttempt.orderId(), createdAttempt.userId(),
                    createdAttempt.provider(), createdAttempt.merchantReference(), createdAttempt.expectedAmountVnd(),
                    createdAttempt.currency(), createdAttempt.orderCreatedAt(), createdAttempt.expiresAt(),
                    createdAttempt.createdAt(), RECEIVED_AT, PaymentStatus.SUCCEEDED, "confirmation-txn-" + orderRow.getId(),
                    PAID_AT, PaymentAttempt.ReviewReason.NONE);
            var attemptRow = PaymentAttemptJpaEntity.newSnapshot(succeededAttempt);
            entityManager.persist(attemptRow);
            entityManager.flush();

            var event = new PaymentEvent("vnpay", succeededAttempt.merchantReference() + ":" + succeededAttempt.transactionReference(),
                    succeededAttempt.merchantReference(), succeededAttempt.transactionReference(),
                    succeededAttempt.expectedAmountVnd(), "VND", PaymentEvent.Outcome.SUCCEEDED,
                    PAID_AT, RECEIVED_AT, DIGEST, "vnpay-hmac-sha512;paydate=valid");
            var audit = new PaymentEventAudit(null, attemptRow.getId(), orderRow.getId(), USER_ID, event,
                    event.fingerprint(), PaymentStatus.PENDING, PaymentStatus.SUCCEEDED,
                    PaymentEventAudit.Disposition.APPLIED, PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
            var eventRow = PaymentEventJpaEntity.newSnapshot(audit);
            entityManager.persist(eventRow);
            entityManager.flush();
            return new Ids(orderRow.getId(), attemptRow.getId(), eventRow.getId());
        });
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private record Ids(Integer orderId, Integer attemptId, Integer eventId) { }
}
