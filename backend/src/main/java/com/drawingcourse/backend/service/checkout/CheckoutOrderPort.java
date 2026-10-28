package com.drawingcourse.backend.service.checkout;

import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.service.StoredOrder;

/** REQUIRED Order insert participating in the caller's transaction. */
@FunctionalInterface
public interface CheckoutOrderPort {
    StoredOrder insertNew(Order snapshot);
}
