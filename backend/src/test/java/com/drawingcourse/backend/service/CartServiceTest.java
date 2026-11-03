package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.Cart;
import com.drawingcourse.backend.entity.CartItem;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.dto.CartItemRequest;
import com.drawingcourse.backend.dto.CartItemQuantityRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartServiceTest {
    @SuppressWarnings("unchecked")
    private final CartCatalog<String, String> catalog = mock(CartCatalog.class);
    private final CartService<String, String> service = new CartService<>(catalog);
    private final Cart<String, String> cart = new Cart<>("owner");

    @Test
    void productsAccumulateAndCoursesAreIdempotent() {
        service.add(cart, "owner", product(2));
        service.add(cart, "owner", product(3));
        service.add(cart, "owner", new CartItem<>(CartItemType.COURSE, "sku", 1));
        service.add(cart, "owner", new CartItem<>(CartItemType.COURSE, "sku", 1));
        assertEquals(2, cart.getItems().size());
        assertEquals(5, cart.getItems().getFirst().getQuantity());
        assertEquals(1, cart.getItems().get(1).getQuantity());
    }

    @Test
    void rejectsInvalidQuantitiesAndOverflowWithoutMutation() {
        assertThrows(IllegalArgumentException.class, () -> product(0));
        assertThrows(IllegalArgumentException.class, () -> product(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new CartItem<>(CartItemType.COURSE, "course", 2));
        service.add(cart, "owner", product(Integer.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> service.add(cart, "owner", product(1)));
        assertEquals(Integer.MAX_VALUE, cart.getItems().getFirst().getQuantity());
    }

    @Test
    void everyMutationRejectsAnotherUserBeforeCatalogAccess() {
        assertThrows(SecurityException.class, () -> service.add(cart, "other", product(1)));
        assertThrows(SecurityException.class, () -> service.update(cart, "other", product(1)));
        assertThrows(SecurityException.class,
                () -> service.remove(cart, "other", CartItemType.PRODUCT, "sku"));
        assertThrows(SecurityException.class, () -> service.clear(cart, "other"));
        assertThrows(SecurityException.class, () -> service.clear(cart, null));
        verifyNoInteractions(catalog);
        assertTrue(cart.getItems().isEmpty());
    }

    @Test
    void catalogRejectionLeavesCartUnchanged() {
        CartItem<String> item = product(1);
        doThrow(new IllegalArgumentException("Unavailable or owned"))
                .when(catalog).requirePurchasable("owner", item);
        assertThrows(IllegalArgumentException.class, () -> service.add(cart, "owner", item));
        assertThrows(IllegalArgumentException.class, () -> service.update(cart, "owner", item));
        assertTrue(cart.getItems().isEmpty());
    }

    @Test
    void updatesReplaceQuantityAndRemoveAndClearWorkWithoutCatalog() {
        service.add(cart, "owner", product(2));
        service.update(cart, "owner", product(4));
        assertEquals(4, cart.getItems().getFirst().getQuantity());
        assertThrows(UnsupportedOperationException.class, () -> cart.getItems().clear());
        clearInvocations(catalog);
        assertTrue(service.remove(cart, "owner", CartItemType.PRODUCT, "sku"));
        assertFalse(service.remove(cart, "owner", CartItemType.PRODUCT, "sku"));
        assertThrows(java.util.NoSuchElementException.class,
                () -> service.update(cart, "owner", product(1)));
        service.add(cart, "owner", product(1));
        clearInvocations(catalog);
        service.clear(cart, "owner");
        verifyNoInteractions(catalog);
        assertTrue(cart.getItems().isEmpty());
    }

    @Test
    void requestValidationEnforcesRequiredFieldsAndCourseQuantity() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(new CartItemRequest(null, null, null)).isEmpty());
            assertFalse(validator.validate(new CartItemRequest(CartItemType.COURSE, 1, 2)).isEmpty());
            assertFalse(validator.validate(new CartItemQuantityRequest(0)).isEmpty());
            assertTrue(validator.validate(new CartItemRequest(CartItemType.PRODUCT, 1, 2)).isEmpty());
            assertTrue(validator.validate(new CartItemRequest(CartItemType.COURSE, 1, 1)).isEmpty());
        }
    }

    private CartItem<String> product(int quantity) {
        return new CartItem<>(CartItemType.PRODUCT, "sku", quantity);
    }
}
