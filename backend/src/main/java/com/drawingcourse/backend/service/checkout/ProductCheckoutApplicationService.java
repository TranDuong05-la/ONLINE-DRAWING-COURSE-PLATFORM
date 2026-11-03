package com.drawingcourse.backend.service.checkout;

import com.artacademy.security.AuthenticatedUserIdResolver;
import java.util.Objects;

/** Product/mixed Checkout boundary; the caller's identity always comes from the verified principal. */
public final class ProductCheckoutApplicationService {
    private final AuthenticatedUserIdResolver userIds;
    private final CheckoutCoordinator coordinator;
    public ProductCheckoutApplicationService(AuthenticatedUserIdResolver userIds, CheckoutCoordinator coordinator) {
        this.userIds = Objects.requireNonNull(userIds);
        this.coordinator = Objects.requireNonNull(coordinator);
    }
    public CheckoutCoordinator.CheckoutResult checkout(String key, CheckoutRequest request) {
        return coordinator.checkout(userIds.requireUserId(), key, request);
    }
}
