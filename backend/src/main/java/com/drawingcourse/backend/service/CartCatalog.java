package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.CartItem;

/** Required preflight integration contract; there is deliberately no permissive default adapter. */
@FunctionalInterface
public interface CartCatalog<I, U> {
    /**
     * Before a Cart write transaction begins, reject nonexistent/inactive items and owned or actively
     * pending Courses. This must be a bounded local read, never an HTTP call. Product stock is not a
     * mutation gate here: out-of-stock items remain in the Cart and are projected separately. Never
     * reserve/deduct inventory. Checkout must revalidate all catalog, ownership, and stock facts.
     */
    void requirePurchasable(U userId, CartItem<I> item);
}
