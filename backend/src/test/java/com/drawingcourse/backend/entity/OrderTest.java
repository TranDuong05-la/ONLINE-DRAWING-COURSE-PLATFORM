package com.drawingcourse.backend.entity;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderTest {
    private OrderLineSnapshot product(int id, int quantity, long price, boolean eligible) {
        return new OrderLineSnapshot(OrderItemType.PRODUCT, id, "Supply " + id, quantity, price, eligible);
    }
    private OrderVoucher fixed(long amount) {
        return new OrderVoucher("TEST", OrderVoucher.Kind.FIXED_VND, amount, Long.MAX_VALUE);
    }
    private Order order(List<OrderLineSnapshot> lines, long shipping, OrderVoucher voucher) {
        return Order.create(10, lines, shipping, Optional.ofNullable(voucher));
    }

    @Test void singleCourseOrderHasImmutableAmountsAndNoPaymentClaim() {
        var line = new OrderLineSnapshot(OrderItemType.COURSE, 1, "Drawing", 1, 450000, true);
        var order = order(List.of(line), 0, fixed(50000));
        assertEquals(10, order.getUserId());
        assertEquals("VND", order.getCurrency());
        assertEquals(450000, order.getMerchandiseSubtotalVnd());
        assertEquals(50000, order.getDiscountVnd());
        assertEquals(400000, order.getTotalVnd());
        assertEquals(400000, order.getItems().getFirst().netAmountVnd());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        assertEquals(PaymentStatus.CREATED, order.getPaymentStatus());
        assertEquals(1, order.getItems().getFirst().catalogSnapshot().quantity());
        assertThrows(UnsupportedOperationException.class, () -> order.getItems().clear());
    }

    @Test void mixedOrderExcludesShippingAndIneligibleLinesFromDiscount() {
        var course = new OrderLineSnapshot(OrderItemType.COURSE, 1, "Drawing", 1, 450000, true);
        var order = order(List.of(course, product(1, 2, 125000, true), product(2, 1, 85000, false)),
                30000, fixed(70000));
        assertEquals(785000, order.getMerchandiseSubtotalVnd());
        assertEquals(700000, order.getEligibleSubtotalVnd());
        assertEquals(List.of(45000L, 25000L, 0L),
                order.getItems().stream().map(OrderItem::allocatedDiscountVnd).toList());
        assertEquals(745000, order.getTotalVnd());
        assertEquals(30000, order.getShippingFeeVnd());
        assertReconciles(order);
    }

    @Test void zeroDiscountWithNoVoucherAndZeroEligibleSubtotal() {
        var order = order(List.of(product(1, 2, 10, false)), 5, null);
        assertEquals(0, order.getDiscountVnd());
        assertEquals(25, order.getTotalVnd());
        var zero = order(List.of(product(1, 1, 0, true)), 0, fixed(0));
        assertEquals(0, zero.getTotalVnd());
        assertReconciles(zero);
    }

    @Test void fullDiscountNeverDiscountsShippingOrIneligibleItems() {
        var order = order(List.of(product(1, 2, 10, true), product(2, 1, 7, false)), 4, fixed(20));
        assertEquals(0, order.getItems().getFirst().netAmountVnd());
        assertEquals(11, order.getTotalVnd());
        assertReconciles(order);
    }

    @Test void partialUnitAmountsSumToOriginalNetAndNeverReapplyVoucher() {
        var item = order(List.of(product(1, 3, 10, true)), 0, fixed(1)).getItems().getFirst();
        assertEquals(29, item.netAmountVnd());
        assertEquals(10, item.netAmountForUnits(0, 1));
        assertEquals(10, item.netAmountForUnits(1, 1));
        assertEquals(9, item.netAmountForUnits(2, 1));
        assertEquals(20, item.netAmountForUnits(0, 2));
        assertEquals(19, item.netAmountForUnits(1, 2));
        assertEquals(29, item.netAmountForUnits(0, 3));
        assertThrows(IllegalArgumentException.class, () -> item.netAmountForUnits(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> item.netAmountForUnits(0, 0));
        assertThrows(IllegalArgumentException.class, () -> item.netAmountForUnits(2, 2));
        assertThrows(IllegalArgumentException.class, () -> item.netAmountForUnits(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test void zeroNetAndOneUnitCourseAllocation() {
        var free = order(List.of(product(1, 3, 10, true)), 0, fixed(30)).getItems().getFirst();
        assertEquals(0, free.netAmountForUnits(1, 2));
        var course = new OrderLineSnapshot(OrderItemType.COURSE, 1, "Course", 1, 20, true);
        var item = order(List.of(course), 0, fixed(1)).getItems().getFirst();
        assertEquals(19, item.netAmountForUnits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> item.netAmountForUnits(0, 2));
    }

    @Test void snapshotsDoNotFollowInputListChanges() {
        var lines = new ArrayList<>(List.of(product(1, 1, 100, true)));
        var original = order(lines, 0, fixed(10));
        lines.set(0, product(1, 1, 900, true));
        var later = order(lines, 0, fixed(30));
        assertEquals(90, original.getItems().getFirst().netAmountVnd());
        assertEquals(100, original.getItems().getFirst().catalogSnapshot().unitPriceVnd());
        assertEquals(870, later.getTotalVnd());
    }

    @Test void rejectsInvalidLinesAndOrders() {
        assertThrows(IllegalArgumentException.class, () -> product(1, 0, 10, true));
        assertThrows(IllegalArgumentException.class, () -> product(1, -1, 10, true));
        assertThrows(IllegalArgumentException.class, () -> product(1, 1, -1, true));
        assertThrows(IllegalArgumentException.class, () -> new OrderLineSnapshot(OrderItemType.COURSE, 1, "Course", 2, 10, true));
        assertThrows(IllegalArgumentException.class, () -> new OrderLineSnapshot(OrderItemType.PRODUCT, 1, " ", 1, 10, true));
        assertThrows(IllegalArgumentException.class, () -> order(List.of(), 0, null));
        assertThrows(IllegalArgumentException.class, () -> order(List.of(product(1, 1, 1, true)), -1, null));
        assertThrows(IllegalArgumentException.class, () -> order(List.of(product(1, 1, 1, true), product(1, 1, 1, true)), 0, null));
        assertThrows(NullPointerException.class, () -> Order.create(null, List.of(product(1, 1, 1, true)), 0, Optional.empty()));
    }

    @Test void rejectsInconsistentItemSnapshots() {
        var line = product(1, 2, 10, true);
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(line, 19, 0, 19));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(line, 20, -1, 21));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(line, 20, 21, -1));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(line, 20, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> OrderItem.withDiscount(product(1, 1, 10, false), 1));
    }

    @Test void rejectsOverflowButSupportsLongMaximumAndLargeIntermediateProducts() {
        assertThrows(ArithmeticException.class, () -> product(1, 2, Long.MAX_VALUE, true));
        assertThrows(ArithmeticException.class, () -> order(List.of(product(1, 1, Long.MAX_VALUE, true), product(2, 1, 1, true)), 0, null));
        assertThrows(ArithmeticException.class, () -> order(List.of(product(1, 1, Long.MAX_VALUE, true)), 1, null));
        var maximum = order(List.of(product(1, 1, Long.MAX_VALUE, true)), 0, fixed(Long.MAX_VALUE - 1));
        assertEquals(1, maximum.getTotalVnd());
        assertEquals(1, maximum.getItems().getFirst().netAmountForUnits(0, 1));
        var manyUnits = order(List.of(product(1, Integer.MAX_VALUE, 1, true)), 0, fixed(1)).getItems().getFirst();
        assertEquals(Integer.MAX_VALUE - 1L, manyUnits.netAmountForUnits(0, Integer.MAX_VALUE));
    }

    @Test void percentageRoundsDownThenCapsDiscount() {
        var percent = new OrderVoucher("PERCENT", OrderVoucher.Kind.PERCENT_BASIS_POINTS, 3333, 100);
        assertEquals(3, order(List.of(product(1, 1, 10, true)), 0, percent).getDiscountVnd());
        assertEquals(100, percent.discountFor(10000));
        var full = new OrderVoucher("FULL", OrderVoucher.Kind.PERCENT_BASIS_POINTS, 10000, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, full.discountFor(Long.MAX_VALUE));
        assertEquals(0, full.discountFor(0));
    }

    @Test void rejectsInvalidVoucherValuesAndOversizedFixedDiscounts() {
        assertThrows(IllegalArgumentException.class, () -> fixed(-1));
        assertThrows(IllegalArgumentException.class, () -> new OrderVoucher(" ", OrderVoucher.Kind.FIXED_VND, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OrderVoucher("X", OrderVoucher.Kind.PERCENT_BASIS_POINTS, 10001, 10));
        assertThrows(IllegalArgumentException.class, () -> new OrderVoucher("X", OrderVoucher.Kind.PERCENT_BASIS_POINTS, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> new OrderVoucher("X", OrderVoucher.Kind.FIXED_VND, 11, 10));
        assertThrows(IllegalArgumentException.class, () -> fixed(1).discountFor(-1));
        assertThrows(IllegalArgumentException.class, () -> order(List.of(product(1, 1, 10, true)), 100, fixed(11)));
        assertThrows(IllegalArgumentException.class, () -> order(List.of(product(1, 1, 10, false)), 0, fixed(1)));
    }

    @Test void generatedOrdersReconcileIncludingEveryPartialUnit() {
        var random = new Random(12457);
        for (int sample = 0; sample < 250; sample++) {
            List<OrderLineSnapshot> lines = new ArrayList<>();
            for (int id = 1; id <= 5; id++) lines.add(product(id, 1 + random.nextInt(5), random.nextInt(1000), random.nextBoolean()));
            long eligible = lines.stream().filter(OrderLineSnapshot::voucherEligible).mapToLong(OrderLineSnapshot::grossAmountVnd).sum();
            var order = order(lines, random.nextInt(100), fixed(random.nextLong(eligible + 1)));
            assertReconciles(order);
            for (var item : order.getItems()) {
                long unitSum = 0;
                for (int i = 0; i < item.catalogSnapshot().quantity(); i++) unitSum += item.netAmountForUnits(i, 1);
                assertEquals(item.netAmountVnd(), unitSum);
            }
        }
    }

    private void assertReconciles(Order order) {
        assertEquals(order.getDiscountVnd(), order.getItems().stream().mapToLong(OrderItem::allocatedDiscountVnd).sum());
        assertEquals(order.getTotalVnd(), order.getItems().stream().mapToLong(OrderItem::netAmountVnd).sum() + order.getShippingFeeVnd());
        for (var item : order.getItems()) {
            assertTrue(item.netAmountVnd() >= 0);
            assertEquals(item.grossAmountVnd(), item.allocatedDiscountVnd() + item.netAmountVnd());
        }
    }
}
