package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderPersistenceMapperTest {
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");
    private final OrderPersistenceMapper mapper = new OrderPersistenceMapper();
    private Order order() {
        return Order.create(10, List.of(
                new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "Brushes", 3, 10, true),
                new OrderLineSnapshot(OrderItemType.COURSE, 1, "Drawing", 1, 50, true)),
                5, Optional.of(new OrderVoucher("OLD", OrderVoucher.Kind.FIXED_VND, 1, 1)));
    }

    @Test void roundTripPreservesEveryAmountItemIdentityPositionAndMetadata() {
        var domain = order();
        var expiry = domain.getExpiresAt();
        var row = mapper.toNewEntity(domain);
        ReflectionTestUtils.setField(row, "id", 20);
        ReflectionTestUtils.setField(row, "version", 4L);

        ReflectionTestUtils.setField(row, "updatedAt", expiry.minusSeconds(20));
        for (int i = 0; i < row.getItems().size(); i++) ReflectionTestUtils.setField(row.getItems().get(i), "id", 30 + i);
        var firstIdentity = row.getItems().getFirst();
        var stored = mapper.toStored(row);
        assertEquals(20, stored.id());
        assertEquals(4L, stored.version());
        assertEquals(expiry, stored.paymentExpiresAt());
        assertEquals(domain.getCreatedAt(), stored.createdAt());
        assertEquals(domain.getCreatedAt(), stored.snapshot().getCreatedAt());
        assertEquals(expiry, stored.snapshot().getExpiresAt());
        assertEquals(expiry.minusSeconds(20), stored.updatedAt());
        assertEquals(List.of(30, 31), stored.itemIdentities().stream().map(StoredOrder.ItemIdentity::id).toList());
        assertEquals(List.of(0, 1), stored.itemIdentities().stream().map(StoredOrder.ItemIdentity::linePosition).toList());
        assertEquals(domain.getItems(), stored.snapshot().getItems());
        assertEquals(domain.getVoucher(), stored.snapshot().getVoucher());
        assertEquals(domain.getTotalVnd(), stored.snapshot().getTotalVnd());
        assertEquals(domain.getEligibleSubtotalVnd(), stored.snapshot().getEligibleSubtotalVnd());
        assertEquals(domain.getMerchandiseSubtotalVnd(), stored.snapshot().getMerchandiseSubtotalVnd());
        assertEquals(domain.getDiscountVnd(), stored.snapshot().getDiscountVnd());
        assertEquals(domain.getShippingFeeVnd(), stored.snapshot().getShippingFeeVnd());
        assertSame(firstIdentity, row.getItems().getFirst());
        assertEquals(stored.itemIdentities(), mapper.toStored(row).itemIdentities());
        var copied = mapper.toDomain(mapper.toNewEntity(stored.snapshot()));
        assertEquals(domain.getItems(), copied.getItems());
        assertThrows(UnsupportedOperationException.class, () -> row.getItems().clear());
        assertThrows(UnsupportedOperationException.class, () -> stored.itemIdentities().clear());
    }

    @Test void restoresHistoricalRoundingWithoutCallingCurrentVoucherCalculation() {
        // Legacy half-up 50% of 5 = 3. Current creation policy floors it to 2.
        var line = new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "Historical name", 1, 5, true);
        var voucher = new OrderVoucher("HALF", OrderVoucher.Kind.PERCENT_BASIS_POINTS, 5000, 100);
        var historic = Order.restore(new Order.Snapshot(10, List.of(new OrderItem(line, 5, 3, 2)),
                Optional.of(voucher), "VND", OrderStatus.CONFIRMED, PaymentStatus.SUCCEEDED,
                5, 5, 3, 0, 2), CREATED, CREATED.plusSeconds(900));
        var row = mapper.toNewEntity(historic);
        var current = Order.create(10, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 1,
                "New catalog name", 1, 1000, true)), 0, Optional.of(new OrderVoucher("HALF",
                OrderVoucher.Kind.PERCENT_BASIS_POINTS, 1000, 100)));
        assertEquals(100, current.getDiscountVnd());
        assertEquals(2, voucher.discountFor(5));
        var restored = mapper.toDomain(row);
        assertEquals(3, restored.getDiscountVnd());
        assertEquals(CREATED, restored.getCreatedAt());
        assertEquals(CREATED.plusSeconds(900), restored.getExpiresAt());
        assertEquals(2, restored.getTotalVnd());
        assertEquals("Historical name", restored.getItems().getFirst().catalogSnapshot().name());
        assertEquals(OrderStatus.CONFIRMED, restored.getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, restored.getPaymentStatus());
        assertEquals(2, restored.getItems().getFirst().netAmountForUnits(0, 1));
    }

    @Test void preservesLegacyRemainderAllocationInsteadOfRunningAllocator() {
        var a = new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "A", 1, 1, true);
        var b = new OrderLineSnapshot(OrderItemType.PRODUCT, 2, "B", 1, 1, true);
        var historical = Order.restore(new Order.Snapshot(10, List.of(new OrderItem(a, 1, 0, 1),
                new OrderItem(b, 1, 1, 0)), Optional.of(new OrderVoucher("ONE", OrderVoucher.Kind.FIXED_VND, 1, 1)),
                "VND", OrderStatus.COMPLETED, PaymentStatus.SUCCEEDED, 2, 2, 1, 0, 1), CREATED, CREATED.plusSeconds(900));
        var restored = mapper.toDomain(mapper.toNewEntity(historical));
        assertEquals(List.of(0L, 1L), restored.getItems().stream().map(OrderItem::allocatedDiscountVnd).toList());
    }

    @Test void missingVoucherStillHasFixedExpiration() {
        var snapshot = Order.create(10, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "A", 1, 0, false)),
                0, Optional.empty());
        var row = mapper.toNewEntity(snapshot);
        assertNull(row.getVoucherCode());
        assertEquals(snapshot.getCreatedAt().plusSeconds(1800), row.getPaymentExpiresAt());
        assertTrue(mapper.toDomain(row).getVoucher().isEmpty());
        assertEquals(0, mapper.toDomain(row).getTotalVnd());
    }

    @Test void invalidStoredTotalsFailRatherThanBeingRepaired() {
        var row = mapper.toNewEntity(order());
        ReflectionTestUtils.setField(row, "totalVnd", 999L);
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(row));
        var invalidItem = mapper.toNewEntity(order());
        ReflectionTestUtils.setField(invalidItem.getItems().getFirst(), "netAmountVnd", 999L);
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(invalidItem));
    }

    @Test void rejectsIncompleteVoucherInvalidCurrencyAndLinePositions() {
        var row = mapper.toNewEntity(order());
        ReflectionTestUtils.setField(row, "voucherValue", null);
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(row));
        var currencyRow = mapper.toNewEntity(order());
        ReflectionTestUtils.setField(currencyRow, "currency", "USD");
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(currencyRow));
        var positions = mapper.toNewEntity(order());
        ReflectionTestUtils.setField(positions.getItems().getFirst(), "linePosition", 1);
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(positions));
    }

    @Test void restoreRejectsDuplicatesAbsentVoucherDiscountsAndOverflow() {
        var line = new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "A", 1, 1, true);
        var item = new OrderItem(line, 1, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> Order.restore(new Order.Snapshot(10, List.of(item, item),
                Optional.empty(), "VND", OrderStatus.PENDING_PAYMENT, PaymentStatus.CREATED, 2, 2, 0, 0, 2), CREATED, CREATED.plusSeconds(1800)));
        assertThrows(IllegalArgumentException.class, () -> Order.restore(new Order.Snapshot(10,
                List.of(new OrderItem(line, 1, 1, 0)), Optional.empty(), "VND", OrderStatus.PENDING_PAYMENT,
                PaymentStatus.CREATED, 1, 1, 1, 0, 0), CREATED, CREATED.plusSeconds(1800)));
        var huge = new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "A", 1, Long.MAX_VALUE, true);
        assertThrows(ArithmeticException.class, () -> Order.restore(new Order.Snapshot(10,
                List.of(new OrderItem(huge, Long.MAX_VALUE, 0, Long.MAX_VALUE)), Optional.empty(), "VND",
                OrderStatus.PENDING_PAYMENT, PaymentStatus.CREATED, Long.MAX_VALUE, Long.MAX_VALUE, 0, 1, Long.MAX_VALUE), CREATED, CREATED.plusSeconds(1800)));
    }
}
