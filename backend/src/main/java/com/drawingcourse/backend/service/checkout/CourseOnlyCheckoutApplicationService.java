package com.drawingcourse.backend.service.checkout;

import com.artacademy.security.AuthenticatedUserIdResolver;
import java.util.Objects;

/** Authenticated application boundary for the explicitly limited Course-only Checkout mode. */
public final class CourseOnlyCheckoutApplicationService {
    private final AuthenticatedUserIdResolver userIds;
    private final CheckoutCoordinator coordinator;

    public CourseOnlyCheckoutApplicationService(AuthenticatedUserIdResolver userIds,
                                                 CheckoutCoordinator coordinator) {
        this.userIds = Objects.requireNonNull(userIds);
        this.coordinator = Objects.requireNonNull(coordinator);
    }

    public CheckoutCoordinator.CheckoutResult checkout(String idempotencyKey, CheckoutRequest request) {
        return coordinator.checkoutCoursesOnly(userIds.requireUserId(), idempotencyKey, request);
    }
}
