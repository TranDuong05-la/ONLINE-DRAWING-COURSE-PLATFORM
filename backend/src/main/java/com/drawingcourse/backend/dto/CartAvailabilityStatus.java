package com.drawingcourse.backend.dto;

/** Current catalog/stock state, not a reservation or promise of future availability. */
public enum CartAvailabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    OUT_OF_STOCK,
    /**
     * Not payable on its own right now because another line in the same Cart already covers the same Course(s)
     * (a Course inside a Combo that is also in the Cart). Never blocks Checkout and is excluded from the subtotal:
     * buying the covering line is what pays for it.
     */
    COVERED
}
