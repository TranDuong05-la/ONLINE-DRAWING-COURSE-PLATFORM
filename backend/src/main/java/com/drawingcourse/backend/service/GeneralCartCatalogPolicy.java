package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.CartItem;
import com.drawingcourse.backend.entity.CartItemType;
import java.time.Clock;
import java.util.Objects;

/** Trusted add/update validation for the general Course + Product Cart. */
public final class GeneralCartCatalogPolicy implements CartCatalog<Integer, Integer> {
    private final CourseCartCatalogProvider courses;
    private final CourseOwnershipProvider ownership;
    private final ActivePendingCoursePurchaseProvider pending;
    private final ProductCartCatalogProvider products;
    private final Clock clock;

    public GeneralCartCatalogPolicy(CourseCartCatalogProvider courses, CourseOwnershipProvider ownership,
            ActivePendingCoursePurchaseProvider pending, ProductCartCatalogProvider products, Clock clock) {
        this.courses = Objects.requireNonNull(courses);
        this.ownership = Objects.requireNonNull(ownership);
        this.pending = Objects.requireNonNull(pending);
        this.products = Objects.requireNonNull(products);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public void requirePurchasable(Integer userId, CartItem<Integer> item) {
        if (userId == null || userId <= 0 || item.getItemId() == null || item.getItemId() <= 0)
            throw new IllegalArgumentException("Cart identity must be positive");
        if (item.getItemType() == CartItemType.COURSE) {
            var course = courses.findCourse(item.getItemId())
                    .orElseThrow(() -> new CartPolicyConflictException("Course is unavailable for purchase"));
            if (!course.active() || ownership.isOwnedBy(userId, item.getItemId())
                    || pending.hasActivePendingPurchase(userId, item.getItemId(), clock.instant()))
                throw new CartPolicyConflictException("Course is unavailable for this account");
            return;
        }
        if (item.getItemType() == CartItemType.PRODUCT) {
            var product = products.findProduct(item.getItemId())
                    .orElseThrow(() -> new CartPolicyConflictException("Product is unavailable for purchase"));
            if (!product.active()) throw new CartPolicyConflictException("Product is unavailable for purchase");
            return; // Stock is display-only here; checkout reserves atomically.
        }
        throw new CartItemTypeNotSupportedException("Unsupported Cart item type");
    }
}
