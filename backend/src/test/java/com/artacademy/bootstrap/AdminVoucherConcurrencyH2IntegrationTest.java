package com.artacademy.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.exception.DuplicateResourceException;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.dto.AdminVoucherStatusRequest;
import com.drawingcourse.backend.dto.AdminVoucherUpdateRequest;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderJpaEntity;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.entity.Voucher;
import com.drawingcourse.backend.entity.VoucherJpaEntity;
import com.drawingcourse.backend.repository.VoucherClaimRepository;
import com.drawingcourse.backend.repository.VoucherRepository;
import com.drawingcourse.backend.service.AdminVoucherService;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.StoredOrder;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.checkout.VoucherReservationPort;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.voucher.admin-api-enabled=true",
        "spring.datasource.url=jdbc:h2:mem:admin-voucher-concurrency;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;LOCK_TIMEOUT=10000"
})
@ActiveProfiles("test")
class AdminVoucherConcurrencyH2IntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-31T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired VoucherRepository vouchers;
    @Autowired VoucherClaimRepository claims;
    @Autowired JpaVoucherReservationService reservations;
    @Autowired AdminVoucherService admin;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void firstReservationSerializesBeforeAdminUpdateAndMakesTermsImmutable() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch reservationHasLock = new CountDownLatch(1);
        CountDownLatch adminHasStarted = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var reservation = pool.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    VoucherReservationPort.Quote quote = reservations.quoteAndLock(fixture.userId,
                            fixture.code, 10_000, NOW).orElseThrow();
                    reservationHasLock.countDown();
                    await(adminHasStarted);
                    reservations.reserveForOrder(fixture.userId, fixture.storedOrder, fixture.order, quote, NOW);
                });
                return "reserved";
            });
            var update = pool.submit(() -> {
                await(reservationHasLock);
                adminHasStarted.countDown();
                try {
                    admin.update(fixture.voucherId, new AdminVoucherUpdateRequest(1L,
                            OrderVoucher.Kind.FIXED_VND, 2_000L, null,
                            NOW.minusSeconds(60), NOW.plusSeconds(3_600), 0L,
                            10L, 2L, null, null));
                    return "updated";
                } catch (DuplicateResourceException conflict) {
                    return conflict.getMessage();
                }
            });

            assertThat(reservation.get(10, TimeUnit.SECONDS)).isEqualTo("reserved");
            assertThat(update.get(10, TimeUnit.SECONDS))
                    .isEqualTo("Voucher financial terms are immutable after the first claim");
        }

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            VoucherJpaEntity row = vouchers.findByIdForUpdate(fixture.voucherId).orElseThrow();
            assertThat(row.getDefinitionVersion()).isEqualTo(1);
            assertThat(row.getValue()).isEqualTo(1_000);
            assertThat(row.getReservedCount()).isEqualTo(1);
            assertThat(claims.existsByVoucherId(fixture.voucherId)).isTrue();
            var claim = claims.findByOrderIdAndUserId(fixture.orderId, fixture.userId).orElseThrow();
            assertThat(claim.getValueSnapshot()).isEqualTo(1_000);
            assertThat(claim.getDiscountVnd()).isEqualTo(1_000);
        });
    }

    @Test
    void concurrentStatusUpdatesWithSameVersionCannotSilentlyOverwrite() throws Exception {
        Integer voucherId = new TransactionTemplate(transactionManager).execute(status -> {
            VoucherJpaEntity row = VoucherJpaEntity.fromDefinition(definition("STATUS-" + shortId()));
            em.persist(row);
            em.flush();
            return row.getId();
        });
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> statusResult(voucherId, start));
            var second = pool.submit(() -> statusResult(voucherId, start));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("changed", "stale");
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            VoucherJpaEntity row = vouchers.findByIdForUpdate(voucherId).orElseThrow();
            assertThat(row.isActive()).isFalse();
            assertThat(row.getDefinitionVersion()).isEqualTo(2);
        });
    }

    @Test
    void actualStatusChangeInvalidatesOldQuoteAndInactiveVoucherCannotBeQuotedAgain() {
        Fixture fixture = fixture();
        VoucherReservationPort.Quote oldQuote = new TransactionTemplate(transactionManager).execute(status ->
                reservations.quoteAndLock(fixture.userId, fixture.code, 10_000, NOW).orElseThrow());

        admin.updateStatus(fixture.voucherId, new AdminVoucherStatusRequest(false, 1L));

        Optional<VoucherReservationPort.Quote> inactiveQuote = new TransactionTemplate(transactionManager)
                .execute(status -> reservations.quoteAndLock(fixture.userId, fixture.code, 10_000, NOW));
        assertThat(inactiveQuote).isEmpty();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                reservations.reserveForOrder(fixture.userId, fixture.storedOrder, fixture.order, oldQuote, NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Voucher definition changed after it was quoted");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(claims.existsByVoucherId(fixture.voucherId)).isFalse();
            VoucherJpaEntity row = vouchers.findByIdForUpdate(fixture.voucherId).orElseThrow();
            assertThat(row.getReservedCount()).isZero();
            assertThat(row.getRedeemedCount()).isZero();
            assertThat(row.getDefinitionVersion()).isEqualTo(2);
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

    private Fixture fixture() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            User user = users.saveAndFlush(User.builder().fullName("Admin voucher concurrency")
                    .email("admin-voucher-" + shortId() + "@example.test")
                    .passwordHash("test-hash").role(Role.USER).isActive(true).build());
            Voucher definition = definition("RACE-" + shortId());
            VoucherJpaEntity voucher = VoucherJpaEntity.fromDefinition(definition);
            em.persist(voucher);
            em.flush();
            Order order = Order.create(user.getId(), List.of(new OrderLineSnapshot(
                    OrderItemType.COURSE, 701, "Course snapshot", 1, 10_000, true)), 0,
                    Optional.of(definition.toOrderSnapshot()), CLOCK);
            OrderJpaEntity orderRow = OrderJpaEntity.fromSnapshot(order);
            em.persist(orderRow);
            em.flush();
            StoredOrder stored = new OrderPersistenceMapper().toStored(orderRow);
            return new Fixture(user.getId(), voucher.getId(), orderRow.getId(), definition.code(), order, stored);
        });
    }

    private static Voucher definition(String code) {
        return new Voucher(code, OrderVoucher.Kind.FIXED_VND, 1_000, OptionalLong.empty(), true,
                NOW.minusSeconds(60), NOW.plusSeconds(3_600), 0,
                OptionalLong.of(10), OptionalLong.of(2));
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

    private record Fixture(Integer userId, Integer voucherId, Integer orderId, String code,
                           Order order, StoredOrder storedOrder) { }
}
