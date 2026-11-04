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
    /** Null when Combos are not part of this deployment; Combo items are then rejected as unsupported. */
    private final ComboCartCatalogProvider combos;

    public GeneralCartCatalogPolicy(CourseCartCatalogProvider courses, CourseOwnershipProvider ownership,
            ActivePendingCoursePurchaseProvider pending, ProductCartCatalogProvider products, Clock clock) {
        this(courses, ownership, pending, products, clock, null);
    }

    public GeneralCartCatalogPolicy(CourseCartCatalogProvider courses, CourseOwnershipProvider ownership,
            ActivePendingCoursePurchaseProvider pending, ProductCartCatalogProvider products, Clock clock,
            ComboCartCatalogProvider combos) {
        this.combos = combos;
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
            var course = courses.findCourse(item.getItemId()).orElseThrow(() -> new CartPolicyConflictException(
                    courses.requiresGift(item.getItemId()) ? CartPolicyMessages.COURSE_GIFT
                            : CartPolicyMessages.COURSE_NOT_FOR_SALE));
            if (!course.active()) throw new CartPolicyConflictException(CartPolicyMessages.COURSE_NOT_FOR_SALE);
            if (ownership.isOwnedBy(userId, item.getItemId()))
                throw new CartPolicyConflictException(CartPolicyMessages.COURSE_OWNED);
            if (pending.hasActivePendingPurchase(userId, item.getItemId(), clock.instant()))
                throw new CartPolicyConflictException(CartPolicyMessages.COURSE_PENDING);
            return;
        }
        if (item.getItemType() == CartItemType.COMBO) {
            if (combos == null) throw new CartItemTypeNotSupportedException(CartPolicyMessages.TYPE_NOT_SUPPORTED);
            var combo = combos.findCombo(item.getItemId()).orElseThrow(() -> new CartPolicyConflictException(
                    combos.requiresGift(item.getItemId()) ? CartPolicyMessages.COMBO_GIFT
                            : CartPolicyMessages.COMBO_NOT_FOR_SALE));
            if (!combo.active()) throw new CartPolicyConflictException(CartPolicyMessages.COMBO_NOT_FOR_SALE);
            for (Integer courseId : combo.courseIds()) {
                if (ownership.isOwnedBy(userId, courseId))
                    throw new CartPolicyConflictException(CartPolicyMessages.COMBO_OWNED);
                if (pending.hasActivePendingPurchase(userId, courseId, clock.instant()))
                    throw new CartPolicyConflictException(CartPolicyMessages.COMBO_PENDING);
            }
            return;
        }
        if (item.getItemType() == CartItemType.PRODUCT) {
            var product = products.findProduct(item.getItemId())
                    .orElseThrow(() -> new CartPolicyConflictException(CartPolicyMessages.PRODUCT_NOT_FOR_SALE));
            if (!product.active()) throw new CartPolicyConflictException(CartPolicyMessages.PRODUCT_NOT_FOR_SALE);
            return; // Stock is display-only here; checkout reserves atomically.
        }
        throw new CartItemTypeNotSupportedException(CartPolicyMessages.TYPE_NOT_SUPPORTED);
    }
}
