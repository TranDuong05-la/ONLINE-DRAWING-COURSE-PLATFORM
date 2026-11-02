package com.drawingcourse.backend.service.checkout;

import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.service.OrderPersistenceService;
import com.drawingcourse.backend.service.StoredOrder;

import java.util.Objects;

/** Explicit adapter over OrderPersistenceService.insertNew (REQUIRED). Not a Spring bean. */
public final class OrderPersistenceCheckoutPort implements CheckoutOrderPort {
    private final OrderPersistenceService orders;
    public OrderPersistenceCheckoutPort(OrderPersistenceService orders) { this.orders = Objects.requireNonNull(orders); }
    @Override public StoredOrder insertNew(Order snapshot) { return orders.insertNew(snapshot); }
}
