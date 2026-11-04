package com.drawingcourse.backend.service;

import com.drawingcourse.backend.dto.CartAvailabilityStatus;
import com.drawingcourse.backend.dto.CartResponse;
import com.drawingcourse.backend.entity.CartItemType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Display-only Course/Combo Cart projection; it performs trusted reads and no purchase-side writes. */
public final class CourseOnlyCartProjector {
    private static final String UNAVAILABLE = "Course is unavailable or not eligible for this account.";
    private static final String COMBO_UNAVAILABLE = "Combo is unavailable or not eligible for this account.";
    private static final String COMBO_OVERLAP =
            "This item covers a Course that is already in your Cart. Remove one of them.";
    private final CourseCartCatalogProvider courses;
    private final CourseOwnershipProvider ownership;
    private final ActivePendingCoursePurchaseProvider pending;
    private final Clock clock;
    /** Null when Combos are not part of this deployment. */
    private final ComboCartCatalogProvider combos;

    public CourseOnlyCartProjector(CourseCartCatalogProvider courses, CourseOwnershipProvider ownership,
            ActivePendingCoursePurchaseProvider pending, Clock clock) {
        this(courses, ownership, pending, clock, null);
    }

    public CourseOnlyCartProjector(CourseCartCatalogProvider courses, CourseOwnershipProvider ownership,
            ActivePendingCoursePurchaseProvider pending, Clock clock, ComboCartCatalogProvider combos) {
        this.courses = Objects.requireNonNull(courses);
        this.ownership = Objects.requireNonNull(ownership);
        this.pending = Objects.requireNonNull(pending);
        this.clock = Objects.requireNonNull(clock);
        this.combos = combos;
    }

    public CartResponse project(CartState cart) {
        Objects.requireNonNull(cart, "cart");
        if (cart.items().stream().anyMatch(item -> item.itemType() == CartItemType.PRODUCT
                || item.itemType() == CartItemType.COMBO && combos == null)) {
            throw new CartItemTypeNotSupportedException("Course-only Cart cannot project Product items");
        }
        List<CartResponse.Item> lines = new ArrayList<>(cart.items().size());
        List<CartResponse.AvailabilityWarning> warnings = new ArrayList<>();
        // Courses already covered by an earlier line (standalone or inside a Combo): a later line that covers the
        // same Course cannot be bought together with it (checkout rejects duplicate coverage).
        Set<Integer> covered = new HashSet<>();
        long subtotal = 0;
        for (CartState.Item item : cart.items()) {
            CartResponse.Item line = item.itemType() == CartItemType.COMBO
                    ? projectCombo(cart, item, covered) : projectCourse(cart, item, covered);
            if (line.availabilityStatus() == CartAvailabilityStatus.AVAILABLE) {
                subtotal = Math.addExact(subtotal, line.lineTotalVnd());
            }
            lines.add(line);
            if (line.availabilityStatus() != CartAvailabilityStatus.AVAILABLE) {
                warnings.add(new CartResponse.AvailabilityWarning(item.cartItemId(), line.availabilityStatus(),
                        line.availabilityMessage()));
            }
        }
        return new CartResponse(cart.cartId(), cart.revision(), lines, subtotal, "VND", !warnings.isEmpty(),
                !lines.isEmpty() && warnings.isEmpty(), warnings);
    }

    private CartResponse.Item projectCourse(CartState cart, CartState.Item item, Set<Integer> covered) {
        Optional<CourseCartCatalogProvider.CourseListing> found = courses.findCourse(item.itemId());
        if (found.isEmpty()) return unavailable(item, null, UNAVAILABLE);
        var course = found.get();
        boolean eligible = course.active() && !ownership.isOwnedBy(cart.userId(), item.itemId())
                && !pending.hasActivePendingPurchase(cart.userId(), item.itemId(), clock.instant());
        if (!eligible) return unavailable(item, course.name(), UNAVAILABLE);
        if (!covered.add(item.itemId())) return unavailable(item, course.name(), COMBO_OVERLAP);
        long total = Math.multiplyExact(course.currentPriceVnd(), item.quantity());
        return new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(),
                course.name(), CartAvailabilityStatus.AVAILABLE, course.currentPriceVnd(), total, null);
    }

    private CartResponse.Item projectCombo(CartState cart, CartState.Item item, Set<Integer> covered) {
        Optional<ComboCartCatalogProvider.ComboListing> found = combos.findCombo(item.itemId());
        if (found.isEmpty()) return unavailable(item, null, COMBO_UNAVAILABLE);
        var combo = found.get();
        boolean eligible = combo.active();
        for (Integer courseId : combo.courseIds()) {
            if (!eligible) break;
            eligible = !ownership.isOwnedBy(cart.userId(), courseId)
                    && !pending.hasActivePendingPurchase(cart.userId(), courseId, clock.instant());
        }
        if (!eligible) return unavailable(item, combo.name(), COMBO_UNAVAILABLE);
        if (combo.courseIds().stream().anyMatch(covered::contains))
            return unavailable(item, combo.name(), COMBO_OVERLAP);
        covered.addAll(combo.courseIds());
        long total = Math.multiplyExact(combo.currentPriceVnd(), item.quantity());
        return new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(),
                combo.name(), CartAvailabilityStatus.AVAILABLE, combo.currentPriceVnd(), total, null);
    }

    private static CartResponse.Item unavailable(CartState.Item item, String name, String reason) {
        return new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(), name,
                CartAvailabilityStatus.UNAVAILABLE, null, null, reason);
    }
}
