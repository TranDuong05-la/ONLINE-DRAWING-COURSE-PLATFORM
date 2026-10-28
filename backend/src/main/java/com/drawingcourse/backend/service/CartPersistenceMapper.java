package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import java.util.HashSet;
import java.util.Set;

public final class CartPersistenceMapper {
    private record Key(CartItemType type, Integer id) { }

    public Cart<Integer, Integer> toDomain(CartJpaEntity record) {
        Cart<Integer, Integer> domain = new Cart<>(record.getUserId());
        Set<Key> seen = new HashSet<>();
        for (CartItemJpaEntity item : record.getItems()) {
            if (!seen.add(new Key(item.getItemType(), item.getItemId()))) {
                throw new IllegalStateException("Duplicate persisted cart reference");
            }
            domain.add(new CartItem<>(item.getItemType(), item.getItemId(), item.getQuantity()));
        }
        return domain;
    }

    /** Synchronize a managed aggregate; never replace surviving rows or their IDs. */
    public boolean synchronize(Cart<Integer, Integer> domain, CartJpaEntity record) {
        if (!record.getUserId().equals(domain.getUserId())) {
            throw new SecurityException("Cart owner mismatch");
        }
        boolean changed = contentDiffers(domain, record);
        Set<Key> retained = new HashSet<>();
        for (CartItem<Integer> item : domain.getItems()) {
            retained.add(new Key(item.getItemType(), item.getItemId()));
            var existing = record.getItems().stream().filter(row ->
                    row.getItemType() == item.getItemType() && row.getItemId().equals(item.getItemId())).findFirst();
            if (existing.isPresent()) { existing.get().setQuantity(item.getQuantity()); }
            else { record.addItem(item.getItemType(), item.getItemId(), item.getQuantity()); }
        }
        for (var row : java.util.List.copyOf(record.getItems())) {
            if (!retained.contains(new Key(row.getItemType(), row.getItemId()))) { record.removeItem(row); }
        }
        return changed;
    }

    public CartState toState(CartJpaEntity record) {
        return new CartState(record.getId(), record.getUserId(), record.getRevision(), record.getItems().stream()
                .map(i -> new CartState.Item(i.getId(), i.getItemType(), i.getItemId(), i.getQuantity())).toList());
    }

    private static boolean contentDiffers(Cart<Integer, Integer> domain, CartJpaEntity record) {
        if (domain.getItems().size() != record.getItems().size()) return true;
        for (var item : domain.getItems()) {
            var existing = record.getItems().stream().filter(row -> row.getItemType() == item.getItemType()
                    && row.getItemId().equals(item.getItemId())).findFirst();
            if (existing.isEmpty() || existing.get().getQuantity() != item.getQuantity()) return true;
        }
        return false;
    }
}
