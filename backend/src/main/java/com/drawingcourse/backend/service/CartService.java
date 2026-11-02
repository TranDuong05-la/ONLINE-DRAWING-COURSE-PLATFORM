package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.Cart;
import com.drawingcourse.backend.entity.CartItem;
import java.util.Objects;

/**
 * Domain policy only, deliberately not a Spring bean or persistence service.
 * Callers must resolve identity from authentication and serialize persisted mutations.
 */
public final class CartService<U, I> {
    private final CartCatalog<I, U> catalog;

    public CartService(CartCatalog<I, U> catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public void add(Cart<U, I> cart, U authenticatedUserId, CartItem<I> item) {
        authorize(cart, authenticatedUserId);
        catalog.requirePurchasable(authenticatedUserId, item);
        cart.add(item);
    }

    public void update(Cart<U, I> cart, U authenticatedUserId, CartItem<I> item) {
        authorize(cart, authenticatedUserId);
        catalog.requirePurchasable(authenticatedUserId, item);
        cart.update(item);
    }

    public boolean remove(Cart<U, I> cart, U authenticatedUserId,
                          com.drawingcourse.backend.entity.CartItemType type, I itemId) {
        authorize(cart, authenticatedUserId);
        return cart.remove(type, itemId);
    }

    public void clear(Cart<U, I> cart, U authenticatedUserId) {
        authorize(cart, authenticatedUserId);
        cart.clear();
    }

    private void authorize(Cart<U, I> cart, U authenticatedUserId) {
        if (authenticatedUserId == null || !cart.getUserId().equals(authenticatedUserId)) {
            throw new SecurityException("Cart does not belong to authenticated user");
        }
    }
}
