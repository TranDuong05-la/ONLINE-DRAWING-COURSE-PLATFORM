package com.drawingcourse.backend.service;

import java.util.Optional;

/** Read-only E-owned product catalog lookup; no stock mutation is part of this contract. */
@FunctionalInterface
public interface ProductCartCatalogProvider {
    Optional<ProductListing> findProduct(Integer productId);

    record ProductListing(Integer productId, String name, long currentPriceVnd, boolean active) {
        public ProductListing {
            if (productId == null || productId <= 0 || name == null || name.isBlank() || currentPriceVnd < 0) {
                throw new IllegalArgumentException("Invalid Product catalog listing");
            }
        }
    }
}
