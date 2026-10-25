package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.entity.CartItemType;
import java.math.BigDecimal;
import java.util.List;

/** Populated only after catalog integration supplies current trusted prices and currency. */
public record CartResponse(List<Item> items, BigDecimal total, String currency) {
    public CartResponse { items = List.copyOf(items); }

    public record Item(String cartItemId, CartItemType itemType, String itemId,
                       int quantity, BigDecimal unitPrice, BigDecimal lineTotal) { }
}
