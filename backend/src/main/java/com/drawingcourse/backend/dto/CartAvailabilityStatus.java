package com.drawingcourse.backend.dto;

/** Current catalog/stock state, not a reservation or promise of future availability. */
public enum CartAvailabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    OUT_OF_STOCK
}
