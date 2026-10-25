package com.drawingcourse.backend.service;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderJpaEntity;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.PaymentStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class ActivePendingCoursePurchaseH2IntegrationTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-25T12:00:00Z");
    private static final AtomicInteger IDENTITIES = new AtomicInteger(10_000);

    @Autowired private ActivePendingCoursePurchaseProvider pendingPurchases;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test void activePendingCourseBlocksForItsOwnerAndMatchingCourse() {
        int userId = userId();
        int courseId = courseId();
        persistOrder(userId, courseId, OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT,
                PaymentStatus.CREATED, OBSERVED_AT.plusSeconds(1));

        assertThat(pendingPurchases.hasActivePendingPurchase(userId, courseId, OBSERVED_AT)).isTrue();
    }

    @Test void ownerAndCoursePredicatesPreventCrossAccountOrCrossCourseMatches() {
        int ownerId = userId();
        int foreignUserId = userId();
        int courseId = courseId();
        int differentCourseId = courseId();
        persistOrder(ownerId, courseId, OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT,
                PaymentStatus.CREATED, OBSERVED_AT.plusSeconds(60));

        assertThat(pendingPurchases.hasActivePendingPurchase(foreignUserId, courseId, OBSERVED_AT)).isFalse();
        assertThat(pendingPurchases.hasActivePendingPurchase(ownerId, differentCourseId, OBSERVED_AT)).isFalse();
    }

    @Test void deadlineIsExclusiveAndMustBeAfterObservedTime() {
        int beforeUser = userId();
        int equalUser = userId();
        int afterUser = userId();
        int courseId = courseId();
        persistOrder(beforeUser, courseId, OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT,
                PaymentStatus.CREATED, OBSERVED_AT.minusNanos(1_000));
        persistOrder(equalUser, courseId, OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT,
                PaymentStatus.CREATED, OBSERVED_AT);
        persistOrder(afterUser, courseId, OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT,
                PaymentStatus.CREATED, OBSERVED_AT.plusNanos(1_000));

        assertThat(pendingPurchases.hasActivePendingPurchase(beforeUser, courseId, OBSERVED_AT)).isFalse();
        assertThat(pendingPurchases.hasActivePendingPurchase(equalUser, courseId, OBSERVED_AT)).isFalse();
        assertThat(pendingPurchases.hasActivePendingPurchase(afterUser, courseId, OBSERVED_AT)).isTrue();
    }

    @Test void nullDeadlineDoesNotCreateAnActiveWindow() {
        int userId = userId();
        int courseId = courseId();
        persistOrder(userId, courseId, OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT,
                PaymentStatus.CREATED, null);

        assertThat(pendingPurchases.hasActivePendingPurchase(userId, courseId, OBSERVED_AT)).isFalse();
    }

    @Test void cancelledExpiredAndConfirmedOrdersDoNotBlock() {
        int courseId = courseId();
        int cancelledUser = userId();
        int expiredUser = userId();
        int confirmedUser = userId();
        persistOrder(cancelledUser, courseId, OrderItemType.COURSE, OrderStatus.CANCELLED,
                PaymentStatus.CANCELLED, OBSERVED_AT.plusSeconds(60));
        persistOrder(expiredUser, courseId, OrderItemType.COURSE, OrderStatus.EXPIRED,
                PaymentStatus.EXPIRED, OBSERVED_AT.plusSeconds(60));
        persistOrder(confirmedUser, courseId, OrderItemType.COURSE, OrderStatus.CONFIRMED,
                PaymentStatus.SUCCEEDED, OBSERVED_AT.plusSeconds(60));

        assertThat(pendingPurchases.hasActivePendingPurchase(cancelledUser, courseId, OBSERVED_AT)).isFalse();
        assertThat(pendingPurchases.hasActivePendingPurchase(expiredUser, courseId, OBSERVED_AT)).isFalse();
        assertThat(pendingPurchases.hasActivePendingPurchase(confirmedUser, courseId, OBSERVED_AT)).isFalse();
    }

    @Test void productOrderItemDoesNotBlockCoursePurchase() {
        int userId = userId();
        int productId = courseId();
        persistOrder(userId, productId, OrderItemType.PRODUCT, OrderStatus.PENDING_PAYMENT,
                PaymentStatus.CREATED, OBSERVED_AT.plusSeconds(60));

        assertThat(pendingPurchases.hasActivePendingPurchase(userId, productId, OBSERVED_AT)).isFalse();
    }

    @Test void paymentAttemptOutcomeDoesNotReleaseAnOtherwiseActivePendingOrder() {
        int userId = userId();
        int courseId = courseId();
        for (PaymentStatus paymentStatus : List.of(PaymentStatus.FAILED, PaymentStatus.UNKNOWN,
                PaymentStatus.SUCCEEDED)) {
            persistOrder(userId, courseId, OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT,
                    paymentStatus, OBSERVED_AT.plusSeconds(60));
        }

        assertThat(pendingPurchases.hasActivePendingPurchase(userId, courseId, OBSERVED_AT)).isTrue();
    }

    @Test void multipleOrdersUseExistenceSemanticsAndStayOwnerScoped() {
        int userId = userId();
        int foreignUserId = userId();
        int courseId = courseId();
        int otherCourseId = courseId();
        int absentCourseId = courseId();
        persistOrder(userId, courseId, OrderItemType.COURSE, OrderStatus.CANCELLED,
                PaymentStatus.CANCELLED, OBSERVED_AT.plusSeconds(60));
        persistOrder(userId, otherCourseId, OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT,
                PaymentStatus.CREATED, OBSERVED_AT.plusSeconds(60));
        persistOrder(foreignUserId, courseId, OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT,
                PaymentStatus.CREATED, OBSERVED_AT.plusSeconds(60));
        persistOrder(userId, courseId, OrderItemType.COURSE, OrderStatus.PENDING_PAYMENT,
                PaymentStatus.FAILED, OBSERVED_AT.plusSeconds(60));

        assertThat(pendingPurchases.hasActivePendingPurchase(userId, courseId, OBSERVED_AT)).isTrue();
        assertThat(pendingPurchases.hasActivePendingPurchase(userId, absentCourseId, OBSERVED_AT)).isFalse();
    }

    private void persistOrder(int userId, int itemId, OrderItemType itemType, OrderStatus status,
            PaymentStatus paymentStatus, Instant paymentExpiresAt) {
        Instant createdAt = OBSERVED_AT.minusSeconds(300);
        Order base = Order.create(userId,
                List.of(new OrderLineSnapshot(itemType, itemId, "Test snapshot", 1, 10_000, true)),
                0, Optional.empty(), Clock.fixed(createdAt, ZoneOffset.UTC));
        var snapshot = new Order.Snapshot(base.getUserId(), base.getItems(), base.getVoucher(), "VND",
                status, paymentStatus, base.getMerchandiseSubtotalVnd(), base.getEligibleSubtotalVnd(),
                base.getDiscountVnd(), base.getShippingFeeVnd(), base.getTotalVnd());
        Order restored = Order.restore(snapshot, createdAt, paymentExpiresAt);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            entityManager.persist(OrderJpaEntity.fromSnapshot(restored));
            entityManager.flush();
        });
    }

    private static int userId() { return IDENTITIES.getAndIncrement(); }
    private static int courseId() { return IDENTITIES.getAndIncrement(); }
}
