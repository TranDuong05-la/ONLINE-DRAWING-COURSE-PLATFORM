package com.drawingcourse.backend.service.checkout;

import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.service.StoredOrder;
import java.util.Optional;

/** REQUIRED Order insert participating in the caller's transaction. */
@FunctionalInterface
public interface CheckoutOrderPort {
    StoredOrder insertNew(Order snapshot);

    /** Physical checkout may persist an immutable address alongside the Order in the same transaction. */
    default StoredOrder insertNew(Order snapshot, Optional<CheckoutRequest.Address> address) {
        return insertNew(snapshot);
    }
}
