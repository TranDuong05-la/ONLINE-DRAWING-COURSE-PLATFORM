package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.CartItemRepository;
import com.drawingcourse.backend.repository.CartRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Explicitly constructed foundation; not a scanned bean. Requires trusted adapters.
 * All writes use fresh transactions so failed first-cart inserts cannot poison retries.
 */
public final class CartApplicationService {
    private static final int MAX_ATTEMPTS = 3;
    private final CartRepository carts;
    private final CartItemRepository items;
    private final CartCurrentUser currentUser;
    private final CartCatalog<Integer, Integer> catalog;
    private final CartPersistenceMapper mapper = new CartPersistenceMapper();
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public CartApplicationService(CartRepository carts, CartItemRepository items,
            CartCurrentUser currentUser, CartCatalog<Integer, Integer> catalog,
            PlatformTransactionManager transactions) {
        this.carts = Objects.requireNonNull(carts);
        this.items = Objects.requireNonNull(items);
        this.currentUser = Objects.requireNonNull(currentUser);
        this.catalog = Objects.requireNonNull(catalog);
        writes = new TransactionTemplate(Objects.requireNonNull(transactions));
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads = new TransactionTemplate(transactions);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public CartState load() {
        return load(userId());
    }

    private CartState load(Integer userId) {
        return reads.execute(status -> carts.findByUserId(userId).map(mapper::toState)
                .orElseGet(() -> empty(userId)));
    }

    public CartState add(CartItemType type, Integer itemId, int quantity) {
        var preflightItem = new CartItem<>(type, itemId, quantity);
        Integer user = userId();
        catalog.requirePurchasable(user, preflightItem); // Never call catalog/provider code under the Cart row lock.
        return mutate(user, true, (record, domain) ->
                domain.add(new CartItem<>(type, itemId, quantity)));
    }

    public CartState update(Integer cartItemId, int quantity) {
        Integer user = userId();
        CartState before = load(user); // This REQUIRES_NEW read completes before catalog validation.
        CartState.Item snapshot = before.items().stream().filter(item -> item.cartItemId().equals(cartItemId))
                .findFirst().orElseThrow(CartApplicationService::notFound);
        var replacement = new CartItem<>(snapshot.itemType(), snapshot.itemId(), quantity);
        catalog.requirePurchasable(user, replacement);
        return mutate(user, false, (record, domain) -> {
            var row = ownedItem(record, cartItemId);
            if (row.getItemType() != replacement.getItemType() || !row.getItemId().equals(replacement.getItemId())) {
                throw notFound();
            }
            domain.update(new CartItem<>(row.getItemType(), row.getItemId(), quantity));
        });
    }

    public CartState remove(Integer cartItemId) {
        Integer user = userId();
        return mutate(user, false, (record, domain) -> {
            var row = ownedItem(record, cartItemId);
            domain.remove(row.getItemType(), row.getItemId());
        });
    }

    public CartState clear() {
        return mutate(userId(), false, (record, domain) -> domain.clear(), true);
    }

    private CartItemJpaEntity ownedItem(CartJpaEntity record, Integer id) {
        var row = items.findByIdAndCart_IdAndCart_UserId(Objects.requireNonNull(id),
                record.getId(), record.getUserId()).orElseThrow(CartApplicationService::notFound);
        // Defensive check also makes the isolation contract explicit for adapters/tests.
        if (!Objects.equals(row.getCart().getId(), record.getId())
                || !record.getUserId().equals(row.getCart().getUserId())) { throw notFound(); }
        return row;
    }

    private CartState mutate(Integer user, boolean create, BiConsumer<CartJpaEntity, Cart<Integer, Integer>> action) {
        return mutate(user, create, action, false);
    }

    private CartState mutate(Integer user, boolean create, BiConsumer<CartJpaEntity, Cart<Integer, Integer>> action,
                             boolean missingIsEmpty) {
        for (int attempt = 1; ; attempt++) {
            try {
                return writes.execute(status -> {
                    var found = carts.findByUserIdForUpdate(user);
                    if (found.isEmpty() && !create) {
                        if (missingIsEmpty) { return empty(user); }
                        throw notFound();
                    }
                    var record = found.orElseGet(() -> createCart(user));
                    if (!user.equals(record.getUserId())) { throw new SecurityException("Cart owner mismatch"); }
                    var domain = mapper.toDomain(record);
                    action.accept(record, domain);
                    mapper.synchronize(domain, record);
                    carts.saveAndFlush(record);
                    return mapper.toState(record);
                });
            } catch (FirstCartRace | PessimisticLockingFailureException conflict) {
                if (attempt >= MAX_ATTEMPTS) { throw conflict; }
            }
        }
    }

    private CartJpaEntity createCart(Integer user) {
        try { return carts.saveAndFlush(new CartJpaEntity(user)); }
        catch (DataIntegrityViolationException failure) {
            // Retry ONLY the named one-cart-per-user constraint, never item/FK/check failures.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation) {
                    String name = violation.getConstraintName();
                    if (name != null && (name.equals("uk_carts_user") || name.endsWith(".uk_carts_user"))) {
                        throw new FirstCartRace(failure);
                    }
                }
            }
            throw failure;
        }
    }

    private Integer userId() {
        Integer id = currentUser.requireUserId();
        if (id == null) { throw new SecurityException("Authentication required"); }
        return id;
    }
    private static CartState empty(Integer user) { return new CartState(null, user, List.of()); }
    private static NoSuchElementException notFound() { return new NoSuchElementException("Cart item not found"); }
    private static final class FirstCartRace extends RuntimeException {
        FirstCartRace(Throwable cause) { super("Concurrent cart creation", cause); }
    }
}
