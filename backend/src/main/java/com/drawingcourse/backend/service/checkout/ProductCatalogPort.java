package com.drawingcourse.backend.service.checkout;

import java.util.Optional;

/** E-owned trusted local catalog read. Never implement with a remote call inside Checkout's transaction. */
@FunctionalInterface
public interface ProductCatalogPort {
    Optional<ProductOffer> findForCheckout(Integer productId);

    record ProductOffer(Integer productId, String name, long unitPriceVnd, boolean purchasable) {
        public ProductOffer {
            if (productId == null || productId <= 0 || name == null || name.isBlank() || unitPriceVnd < 0)
                throw new IllegalArgumentException("Invalid Product offer");
        }
    }
}
