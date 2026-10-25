package com.drawingcourse.backend.entity;

/** In-memory domain aggregate. ID types are supplied by future integration adapters. */
public final class Cart<U, I> {
    private final U userId;
    private final java.util.List<CartItem<I>> items = new java.util.ArrayList<>();

    public Cart(U userId) {
        this.userId = java.util.Objects.requireNonNull(userId, "userId");
    }

    public U getUserId() { return userId; }
    public java.util.List<CartItem<I>> getItems() { return java.util.List.copyOf(items); }

    public void add(CartItem<I> incoming) {
        java.util.Objects.requireNonNull(incoming, "incoming");
        int index = indexOf(incoming.getItemType(), incoming.getItemId());
        if (index < 0) {
            items.add(incoming);
        } else if (incoming.getItemType() == CartItemType.PRODUCT) {
            int quantity = Math.addExact(items.get(index).getQuantity(), incoming.getQuantity());
            items.set(index, new CartItem<>(incoming.getItemType(), incoming.getItemId(), quantity));
        }
    }

    public void update(CartItem<I> replacement) {
        java.util.Objects.requireNonNull(replacement, "replacement");
        int index = indexOf(replacement.getItemType(), replacement.getItemId());
        if (index < 0) { throw new java.util.NoSuchElementException("Cart item not found"); }
        items.set(index, replacement);
    }

    public boolean remove(CartItemType type, I itemId) {
        int index = indexOf(type, itemId);
        if (index < 0) { return false; }
        items.remove(index);
        return true;
    }

    public void clear() { items.clear(); }

    private int indexOf(CartItemType type, I itemId) {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(itemId, "itemId");
        for (int i = 0; i < items.size(); i++) {
            CartItem<I> item = items.get(i);
            if (item.getItemType() == type && item.getItemId().equals(itemId)) { return i; }
        }
        return -1;
    }
}
