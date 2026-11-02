package com.drawingcourse.backend.entity;

/** Domain value only; persistence mappings await the team's schema. */
public final class CartItem<I> {
    private final CartItemType itemType;
    private final I itemId;
    private final int quantity;

    public CartItem(CartItemType itemType, I itemId, int quantity) {
        this.itemType = java.util.Objects.requireNonNull(itemType, "itemType");
        this.itemId = java.util.Objects.requireNonNull(itemId, "itemId");
        if (quantity < 1 || (itemType == CartItemType.COURSE && quantity != 1)) {
            throw new IllegalArgumentException("Products require positive quantities; courses require quantity 1");
        }
        this.quantity = quantity;
    }

    public CartItemType getItemType() { return itemType; }
    public I getItemId() { return itemId; }
    public int getQuantity() { return quantity; }
}
