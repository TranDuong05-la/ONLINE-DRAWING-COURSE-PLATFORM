package com.drawingcourse.backend.service;

/** Advisory catalog state. Checkout must still perform its locked reservation checks. */
public enum VoucherCatalogAvailability {
    POTENTIALLY_AVAILABLE,
    INACTIVE,
    NOT_STARTED,
    EXPIRED,
    TOTAL_QUOTA_EXHAUSTED,
    USER_QUOTA_EXHAUSTED
}
