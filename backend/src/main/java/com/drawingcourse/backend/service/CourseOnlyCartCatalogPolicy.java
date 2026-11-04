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
            throw new CartItemTypeNotSupportedException(CartPolicyMessages.TYPE_NOT_SUPPORTED);
        if (item.getItemId() == null || item.getItemId() <= 0)
            throw new IllegalArgumentException("Course ID must be positive");
        var listing = courses.findCourse(item.getItemId()).orElseThrow(() -> new CartPolicyConflictException(
                courses.requiresGift(item.getItemId()) ? CartPolicyMessages.COURSE_GIFT
                        : CartPolicyMessages.COURSE_NOT_FOR_SALE));
        if (!listing.active()) throw new CartPolicyConflictException(CartPolicyMessages.COURSE_NOT_FOR_SALE);
        if (ownership.isOwnedBy(userId, item.getItemId()))
            throw new CartPolicyConflictException(CartPolicyMessages.COURSE_OWNED);
        if (pending.hasActivePendingPurchase(userId, item.getItemId(), clock.instant()))
            throw new CartPolicyConflictException(CartPolicyMessages.COURSE_PENDING);
    }

    /** A Combo is purchasable only if it is sellable and the account owns none of its Courses and has none pending. */
    private void requireComboPurchasable(Integer userId, CartItem<Integer> item) {
        if (combos == null) throw new CartItemTypeNotSupportedException(CartPolicyMessages.TYPE_NOT_SUPPORTED);
        if (item.getItemId() == null || item.getItemId() <= 0)
            throw new IllegalArgumentException("Combo ID must be positive");
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
    }
}
