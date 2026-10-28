package com.drawingcourse.backend.service.checkout;

/** Narrow interface used by the coordinator; the existing JPA service is its production persistence primitive. */
public interface CheckoutIdempotencyPort {
    CheckoutIdempotencyService.ClaimResult claim(Integer userId, String key, String fingerprint);
    void complete(Integer userId, Integer requestId, Integer orderId);
}
