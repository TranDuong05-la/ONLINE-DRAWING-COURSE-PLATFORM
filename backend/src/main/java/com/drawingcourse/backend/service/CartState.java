package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.CartItemType;
import java.util.List;

/** Persistence identity snapshot. Prices must be obtained separately from the catalog. */
public record CartState(Integer cartId, Integer userId, long revision, List<Item> items) {
    public CartState { items = List.copyOf(items); }
    public CartState(Integer cartId, Integer userId, List<Item> items) { this(cartId, userId, 0L, items); }
    public record Item(Integer cartItemId, CartItemType itemType, Integer itemId, int quantity) { }
}
