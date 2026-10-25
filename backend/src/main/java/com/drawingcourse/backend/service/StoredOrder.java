package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import java.time.Instant;
import java.util.List;

/** Persistence identities/metadata surround the immutable domain; no catalog dependency. */
public record StoredOrder(Integer id, Long version, Instant paymentExpiresAt, Instant createdAt,
                          Instant updatedAt, Order snapshot, List<ItemIdentity> itemIdentities) {
    public StoredOrder { itemIdentities = List.copyOf(itemIdentities); }
    public record ItemIdentity(Integer id, int linePosition, OrderItemType itemType, Integer itemId) { }
}
