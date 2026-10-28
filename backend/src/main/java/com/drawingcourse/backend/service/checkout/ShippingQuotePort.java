package com.drawingcourse.backend.service.checkout;

import java.util.List;
import java.util.Optional;

/** E-owned trusted shipping calculation for Product selections. Returned fee is whole VND. */
@FunctionalInterface
public interface ShippingQuotePort {
    long quoteFeeVnd(Integer authenticatedUserId, List<ProductQuantity> products,
                     Optional<CheckoutRequest.Address> address);

    record ProductQuantity(Integer productId, int quantity) {
        public ProductQuantity {
            if (productId == null || productId <= 0 || quantity <= 0)
                throw new IllegalArgumentException("Invalid shipping product quantity");
        }
    }
}
