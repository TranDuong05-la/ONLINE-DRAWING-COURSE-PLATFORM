package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class CartPersistenceMapperTest {
    private final CartPersistenceMapper mapper = new CartPersistenceMapper();

    @Test void newCartRevisionStartsAtZero() {
        assertEquals(0L, new CartJpaEntity(10).getRevision());
    }

    @Test void roundTripPreservesRowIdentityAndSeparatesTypes() {
        var record = new CartJpaEntity(10);
        record.addItem(CartItemType.PRODUCT, 20, 2);
        record.addItem(CartItemType.COURSE, 20, 1);
        var original = record.getItems().getFirst();
        ReflectionTestUtils.setField(original, "id", 99);
        var domain = mapper.toDomain(record);
        domain.add(new CartItem<>(CartItemType.PRODUCT, 20, 3));
        domain.add(new CartItem<>(CartItemType.COURSE, 20, 1));
        assertTrue(mapper.synchronize(domain, record));
        record.incrementRevision();
        assertSame(original, record.getItems().getFirst());
        assertEquals(99, original.getId());
        assertEquals(5, original.getQuantity());
        assertEquals(2, record.getItems().size());
        assertEquals(1, record.getItems().get(1).getQuantity());
        assertEquals(99, mapper.toState(record).items().getFirst().cartItemId());
    }

    @Test void removesOrphansAndAddsNewRows() {
        var record = new CartJpaEntity(10);
        record.addItem(CartItemType.PRODUCT, 20, 2);
        var domain = mapper.toDomain(record);
        domain.clear();
        domain.add(new CartItem<>(CartItemType.PRODUCT, 21, 1));
        assertTrue(mapper.synchronize(domain, record));
        record.incrementRevision();
        assertEquals(1, record.getItems().size());
        assertEquals(21, record.getItems().getFirst().getItemId());
        assertSame(record, record.getItems().getFirst().getCart());
        assertNotNull(record.getUpdatedAt());
        domain.clear();
        assertTrue(mapper.synchronize(domain, record));
        record.incrementRevision();
        assertTrue(record.getItems().isEmpty());
    }

    @Test void synchronizationReportsNoopWithoutChangingRevision() {
        var record = new CartJpaEntity(10);
        record.addItem(CartItemType.PRODUCT, 20, 2);
        var domain = mapper.toDomain(record);
        assertFalse(mapper.synchronize(domain, record));
        assertEquals(0L, record.getRevision());
        domain.add(new CartItem<>(CartItemType.PRODUCT, 20, 1));
        assertTrue(mapper.synchronize(domain, record));
        record.incrementRevision();
        assertEquals(1L, record.getRevision());
    }

    @Test void rejectsOwnerMismatchAndInvalidPersistenceValues() {
        var record = new CartJpaEntity(10);
        assertThrows(SecurityException.class, () -> mapper.synchronize(new Cart<>(11), record));
        assertThrows(IllegalArgumentException.class, () -> record.addItem(CartItemType.COURSE, 20, 2));
        assertThrows(IllegalArgumentException.class, () -> record.addItem(CartItemType.PRODUCT, 20, 0));
        record.addItem(CartItemType.PRODUCT, 20, 1);
        assertThrows(IllegalArgumentException.class, () -> record.addItem(CartItemType.PRODUCT, 20, 1));
        ReflectionTestUtils.setField(record.getItems().getFirst(), "quantity", 0);
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(record));
    }
}
