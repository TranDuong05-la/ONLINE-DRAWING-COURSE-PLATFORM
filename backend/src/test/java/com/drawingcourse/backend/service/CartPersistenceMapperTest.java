package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class CartPersistenceMapperTest {
    private final CartPersistenceMapper mapper = new CartPersistenceMapper();

    @Test void roundTripPreservesRowIdentityAndSeparatesTypes() {
        var record = new CartJpaEntity(10);
        record.addItem(CartItemType.PRODUCT, 20, 2);
        record.addItem(CartItemType.COURSE, 20, 1);
        var original = record.getItems().getFirst();
        ReflectionTestUtils.setField(original, "id", 99);
        var domain = mapper.toDomain(record);
        domain.add(new CartItem<>(CartItemType.PRODUCT, 20, 3));
        domain.add(new CartItem<>(CartItemType.COURSE, 20, 1));
        mapper.synchronize(domain, record);
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
        mapper.synchronize(domain, record);
        assertEquals(1, record.getItems().size());
        assertEquals(21, record.getItems().getFirst().getItemId());
        assertSame(record, record.getItems().getFirst().getCart());
        assertNotNull(record.getUpdatedAt());
        domain.clear();
        mapper.synchronize(domain, record);
        assertTrue(record.getItems().isEmpty());
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
