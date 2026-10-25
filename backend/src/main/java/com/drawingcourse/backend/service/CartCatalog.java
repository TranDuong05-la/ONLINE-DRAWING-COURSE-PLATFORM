package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.CartItem;

/** Required integration contract; there is deliberately no permissive default adapter. */
@FunctionalInterface
public interface CartCatalog<I, U> {
    /**
     * Reject nonexistent, inactive or unavailable items and already-owned courses.
     * Check the requested resulting quantity when updating. Never reserve/deduct stock.
     */
    void requirePurchasable(U userId, CartItem<I> item);
}
