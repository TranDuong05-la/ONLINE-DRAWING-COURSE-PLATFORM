package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.PaymentStatus;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.OrderItemRepository;
import com.drawingcourse.backend.repository.OrderShippingAddressRepository;
import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Unwired persistence primitives, NOT checkout: no reservations, idempotency or gateway calls. */
public final class OrderPersistenceService {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderCurrentUser currentUser;
    private final OrderShippingAddressRepository shippingAddresses;
    private final OrderPersistenceMapper mapper = new OrderPersistenceMapper();
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public OrderPersistenceService(OrderRepository orders, OrderItemRepository items, OrderCurrentUser currentUser,
                                   PlatformTransactionManager transactions) {
        this(orders, items, currentUser, transactions, null);
    }

    public OrderPersistenceService(OrderRepository orders, OrderItemRepository items, OrderCurrentUser currentUser,
                                   PlatformTransactionManager transactions,
                                   OrderShippingAddressRepository shippingAddresses) {
        this.orders = Objects.requireNonNull(orders);
        this.items = Objects.requireNonNull(items);
        this.currentUser = Objects.requireNonNull(currentUser);
        this.shippingAddresses = shippingAddresses;
        reads = new TransactionTemplate(Objects.requireNonNull(transactions));
        reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public StoredOrder load(Integer orderId) {
        Integer owner = userId();
        Objects.requireNonNull(orderId, "orderId");
        return reads.execute(status -> {
            var row = orders.findByIdAndUserId(orderId, owner).orElseThrow(OrderPersistenceService::notFound);
            if (!owner.equals(row.getUserId()) || !orderId.equals(row.getId())) throw notFound();
            return mapper.toStored(row);
        });
    }

    public StoredOrderItem loadItem(Integer orderId, Integer itemId) {
        Integer owner = userId();
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(itemId, "itemId");
        return reads.execute(status -> {
            var row = items.findByIdAndOrder_IdAndOrder_UserId(itemId, orderId, owner)
                    .orElseThrow(OrderPersistenceService::notFound);
            if (!owner.equals(row.getOrder().getUserId()) || !orderId.equals(row.getOrder().getId())
                    || !itemId.equals(row.getId())) throw notFound();
            return new StoredOrderItem(row.getId(), orderId, row.getLinePosition(), mapper.toDomainItem(row));
        });
    }

    /**
     * Internal future-checkout building block only. Joins an existing transaction.
     * The deadline is fixed by the domain's creation timestamp (BR-ORDER-01).
     * Every call inserts a new order, so never expose this directly as a checkout API.
     */
    public StoredOrder insertNew(Order snapshot) {
        return insertNew(snapshot, java.util.Optional.empty());
    }

    public StoredOrder insertNew(Order snapshot, java.util.Optional<CheckoutRequest.Address> address) {
        Integer owner = userId();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(address, "address");
        if (!owner.equals(snapshot.getUserId())) throw new SecurityException("Order owner mismatch");
        if (snapshot.getStatus() != OrderStatus.PENDING_PAYMENT || snapshot.getPaymentStatus() != PaymentStatus.CREATED) {
            throw new IllegalArgumentException("Only new pending orders can be inserted");
        }
        if (!snapshot.getCreatedAt().plus(Duration.ofMinutes(30)).equals(snapshot.getExpiresAt())) {
            throw new IllegalArgumentException("New orders require the fixed 30-minute deadline");
        }
        return writes.execute(status -> {
            var persisted = orders.saveAndFlush(mapper.toNewEntity(snapshot));
            if (address.isPresent()) {
                if (shippingAddresses == null)
                    throw new IllegalStateException("Shipping address persistence is not configured");
                shippingAddresses.saveAndFlush(new com.drawingcourse.backend.entity.OrderShippingAddressJpaEntity(
                        persisted, address.orElseThrow()));
            }
            return mapper.toStored(persisted);
        });
    }

    private Integer userId() {
        Integer owner = currentUser.requireUserId();
        if (owner == null) throw new SecurityException("Authentication required");
        return owner;
    }
    private static NoSuchElementException notFound() { return new NoSuchElementException("Order or item not found"); }
}
