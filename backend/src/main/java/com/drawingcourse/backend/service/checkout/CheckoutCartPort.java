package com.drawingcourse.backend.service.checkout;

import com.drawingcourse.backend.service.CartState;

/** Locks and reads the persisted Cart in the current Checkout transaction; must not delegate to CartApplicationService. */
@FunctionalInterface
public interface CheckoutCartPort {
    CartState lockForCheckout(Integer authenticatedUserId);
}
