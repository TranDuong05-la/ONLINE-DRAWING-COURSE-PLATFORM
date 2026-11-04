package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.CartItem;
import com.drawingcourse.backend.entity.CartItemType;
import java.time.Clock;
import java.util.Objects;

/** Course-only add/update preflight using trusted B catalog and C ownership/claim evidence. */
public final class CourseOnlyCartCatalogPolicy implements CartCatalog<Integer, Integer> {
    private final CourseCartCatalogProvider courses;
    private final CourseOwnershipProvider ownership;
    private final ActivePendingCoursePurchaseProvider pending;
    private final Clock clock;
    /** Null when Combos are not part of this deployment; Combo items are then rejected as unsupported. */
    private final ComboCartCatalogProvider combos;

    public CourseOnlyCartCatalogPolicy(CourseCartCatalogProvider courses, CourseOwnershipProvider ownership,
            ActivePendingCoursePurchaseProvider pending, Clock clock) {
        this(courses, ownership, pending, clock, null);
    }

    public CourseOnlyCartCatalogPolicy(CourseCartCatalogProvider courses, CourseOwnershipProvider ownership,
            ActivePendingCoursePurchaseProvider pending, Clock clock, ComboCartCatalogProvider combos) {
        this.courses = Objects.requireNonNull(courses);
        this.ownership = Objects.requireNonNull(ownership);
        this.pending = Objects.requireNonNull(pending);
        this.clock = Objects.requireNonNull(clock);
        this.combos = combos;
    }

    @Override
    public void requirePurchasable(Integer userId, CartItem<Integer> item) {
        if (item.getItemType() == CartItemType.COMBO) {
            requireComboPurchasable(userId, item);
            return;
        }
        if (item.getItemType() != CartItemType.COURSE)
            throw new CartItemTypeNotSupportedException("Course-only Cart does not accept Product items");
        if (item.getItemId() == null || item.getItemId() <= 0)
            throw new IllegalArgumentException("Course ID must be positive");
        var listing = courses.findCourse(item.getItemId())
                .orElseThrow(() -> new CartPolicyConflictException("Course is unavailable for purchase"));
        if (!listing.active() || ownership.isOwnedBy(userId, item.getItemId())
                || pending.hasActivePendingPurchase(userId, item.getItemId(), clock.instant())) {
            throw new CartPolicyConflictException("Course is unavailable for this account");
        }
    }

    /** A Combo is purchasable only if it is sellable and the account owns none of its Courses and has none pending. */
    private void requireComboPurchasable(Integer userId, CartItem<Integer> item) {
        if (combos == null) throw new CartItemTypeNotSupportedException("Cart does not accept Combo items");
        if (item.getItemId() == null || item.getItemId() <= 0)
            throw new IllegalArgumentException("Combo ID must be positive");
        var combo = combos.findCombo(item.getItemId())
                .orElseThrow(() -> new CartPolicyConflictException("Combo is unavailable for purchase"));
        if (!combo.active()) throw new CartPolicyConflictException("Combo is unavailable for purchase");
        for (Integer courseId : combo.courseIds()) {
            if (ownership.isOwnedBy(userId, courseId)
                    || pending.hasActivePendingPurchase(userId, courseId, clock.instant()))
                throw new CartPolicyConflictException("Combo is unavailable for this account");
        }
    }
}
