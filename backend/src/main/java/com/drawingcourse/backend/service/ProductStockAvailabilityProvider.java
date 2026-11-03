package com.drawingcourse.backend.service;

/** Read-only E-owned stock availability. This must never reserve or deduct inventory. */
@FunctionalInterface
public interface ProductStockAvailabilityProvider {
    int availableQuantity(Integer productId);
}
