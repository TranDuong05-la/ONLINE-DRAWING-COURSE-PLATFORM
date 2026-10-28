package com.drawingcourse.backend.service.checkout;

import com.drawingcourse.backend.repository.CartRepository;
import com.drawingcourse.backend.service.CartPersistenceMapper;
import com.drawingcourse.backend.service.CartState;

import java.util.Objects;

/** Explicit local adapter: locks the existing Cart row and maps identity/quantity only. Not a Spring bean. */
public final class JpaCheckoutCartPort implements CheckoutCartPort {
    private final CartRepository carts;
    private final CartPersistenceMapper mapper = new CartPersistenceMapper();

    public JpaCheckoutCartPort(CartRepository carts) { this.carts = Objects.requireNonNull(carts); }

    @Override
    public CartState lockForCheckout(Integer authenticatedUserId) {
        return carts.findByUserIdForUpdate(authenticatedUserId).map(mapper::toState).orElse(null);
    }
}
