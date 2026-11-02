package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrderDiscountAllocatorTest {
    private final OrderDiscountAllocator allocator = new OrderDiscountAllocator();
    private OrderLineSnapshot line(int id, long gross, boolean eligible) {
        return new OrderLineSnapshot(OrderItemType.PRODUCT, id, "Product", 1, gross, eligible);
    }

    @Test void proportionAndLargestRemainderAreExact() {
        assertEquals(List.of(1L, 2L, 2L), allocator.allocate(List.of(line(1, 1, true), line(2, 2, true), line(3, 3, true)), 5));
        assertEquals(List.of(10L, 20L), allocator.allocate(List.of(line(1, 100, true), line(2, 200, true)), 30));
    }

    @Test void equalRemaindersUseStableCatalogIdentityNotInputOrder() {
        assertEquals(List.of(0L, 1L), allocator.allocate(List.of(line(2, 1, true), line(1, 1, true)), 1));
        assertEquals(List.of(1L, 0L), allocator.allocate(List.of(line(1, 1, true), line(2, 1, true)), 1));
        var course = new OrderLineSnapshot(OrderItemType.COURSE, 1, "Course", 1, 1, true);
        assertEquals(List.of(0L, 1L), allocator.allocate(List.of(line(1, 1, true), course), 1));
    }

    @Test void zeroGrossAndIneligibleLinesNeverReceiveRemainders() {
        var lines = List.of(line(1, 0, true), line(2, 100, false), line(3, 1, true));
        assertEquals(List.of(0L, 0L, 1L), allocator.allocate(lines, 1));
        assertEquals(List.of(0L, 0L, 0L), allocator.allocate(lines, 0));
        assertEquals(List.of(), allocator.allocate(List.of(), 0));
    }

    @Test void fullDiscountAndLargeIntermediateMultiplication() {
        var lines = List.of(line(1, Long.MAX_VALUE - 1, true), line(2, 1, true));
        assertEquals(List.of(Long.MAX_VALUE - 1, 1L), allocator.allocate(lines, Long.MAX_VALUE));
        assertEquals(List.of(Long.MAX_VALUE - 2, 1L), allocator.allocate(lines, Long.MAX_VALUE - 1));
    }

    @Test void rejectsNegativeOversizedDuplicateAndOverflowInputs() {
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(List.of(line(1, 1, true)), -1));
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(List.of(line(1, 1, true)), 2));
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(List.of(line(1, 1, true), line(1, 1, true)), 0));
        assertThrows(ArithmeticException.class, () -> allocator.allocate(List.of(line(1, Long.MAX_VALUE, true), line(2, 1, true)), 0));
    }
}
