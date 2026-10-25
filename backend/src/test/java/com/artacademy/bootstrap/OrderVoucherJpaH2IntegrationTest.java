package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.VoucherPersistenceMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles("test")
class OrderVoucherJpaH2IntegrationTest {
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderRepository orders;
    @Autowired private OrderShippingAddressRepository addresses;
    @Autowired private VoucherRepository vouchers;
    @Autowired private VoucherUserUsageRepository voucherUsers;

    @Test void orderItemsDeadlineAndSeparateShippingSnapshotPersistAndRestore() {
        Instant created = Instant.parse("2026-10-25T03:04:05.123456Z");
        var order = Order.create(73, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 81,
                "Brush set", 2, 100_000, true)), 2_500, Optional.empty(),
                Clock.fixed(created, ZoneOffset.UTC));
        Integer orderId = new TransactionTemplate(transactionManager).execute(status -> {
            var row = OrderJpaEntity.fromSnapshot(order);
            entityManager.persist(row);
            entityManager.flush();
            entityManager.persist(new OrderShippingAddressJpaEntity(row,
                    new com.drawingcourse.backend.service.checkout.CheckoutRequest.Address(
                            "Receiver", "0900000000", "Street 1", "Ward 2", "700000", "VN")));
            entityManager.flush();
            return row.getId();
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var row = orders.findByIdAndUserId(orderId, 73).orElseThrow();
            var stored = new OrderPersistenceMapper().toStored(row);
            assertEquals(created, stored.createdAt());
            assertEquals(created.plusSeconds(1800), stored.paymentExpiresAt());
            assertEquals(202_500L, stored.snapshot().getTotalVnd());
            assertEquals(200_000L, stored.snapshot().getItems().getFirst().grossAmountVnd());
            assertEquals(200_000L, stored.snapshot().getItems().getFirst().netAmountVnd());
            var address = addresses.findByOrder_IdAndOrder_UserId(orderId, 73).orElseThrow();
            assertEquals("Receiver", address.getRecipient());
            assertEquals("Street 1", address.getAddressLine());
        });
        assertThat(addresses.findByOrder_IdAndOrder_UserId(orderId, 74)).isEmpty();
    }

    @Test void voucherDefinitionUsageMappingVersioningAndStaleWriteProtection() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        var definition = new Voucher("CART-TEST", OrderVoucher.Kind.FIXED_VND, 1_000,
                OptionalLong.empty(), true, from, from.plusSeconds(86_400), 5_000,
                OptionalLong.of(4), OptionalLong.of(2));
        Integer[] ids = new TransactionTemplate(transactionManager).execute(status -> {
            var voucher = VoucherJpaEntity.fromDefinition(definition);
            entityManager.persist(voucher);
            entityManager.flush();
            var usage = new VoucherUserUsageJpaEntity(voucher.getId(), 73);
            entityManager.persist(usage);
            entityManager.flush();
            long firstVersion = usage.getVersion();
            usage.reserveOne();
            entityManager.flush();
            return new Integer[]{voucher.getId(), usage.getId(), (int) firstVersion, usage.getVersion().intValue()};
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.clear();
            var voucher = vouchers.findByCode("CART-TEST").orElseThrow();
            var usage = voucherUsers.findByVoucherIdAndUserId(ids[0], 73).orElseThrow();
            assertEquals(definition, new VoucherPersistenceMapper().toDomain(voucher));
            assertEquals(1, usage.getReservedCount());
            assertThat(usage.getVersion()).isGreaterThan((long) ids[2]);
            assertThat(usage.getVersion()).isGreaterThanOrEqualTo((long) ids[3]);
        });

        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            first.getTransaction().begin();
            second.getTransaction().begin();
            var a = first.find(VoucherUserUsageJpaEntity.class, ids[1]);
            var b = second.find(VoucherUserUsageJpaEntity.class, ids[1]);
            a.reserveOne();
            first.getTransaction().commit();
            b.reserveOne();
            assertThrows(OptimisticLockException.class, second::flush);
        } finally {
            if (first.getTransaction().isActive()) first.getTransaction().rollback();
            if (second.getTransaction().isActive()) second.getTransaction().rollback();
            first.close();
            second.close();
        }
    }

    @Test void transactionalOrderAndVoucherWritesRollbackTogetherOnH2() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        var voucher = new Voucher("ROLLBACK-TEST", OrderVoucher.Kind.FIXED_VND, 500,
                OptionalLong.empty(), true, from, from.plusSeconds(86_400), 0,
                OptionalLong.empty(), OptionalLong.empty());
        var order = Order.create(91, List.of(new OrderLineSnapshot(OrderItemType.COURSE, 13,
                "Course snapshot", 1, 10_000, true)), 0, Optional.empty(),
                Clock.fixed(from, ZoneOffset.UTC));
        var tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            entityManager.persist(OrderJpaEntity.fromSnapshot(order));
            entityManager.persist(VoucherJpaEntity.fromDefinition(voucher));
            entityManager.flush();
            throw new IllegalStateException("intentional rollback test");
        }));
        Long count = new TransactionTemplate(transactionManager).execute(status -> entityManager.createQuery("select count(o) from OrderJpaEntity o where o.userId = :userId", Long.class).setParameter("userId", 91).getSingleResult());
        assertEquals(0L, count);
        assertThat(vouchers.findByCode("ROLLBACK-TEST")).isEmpty();
    }
}
