package com.drawingcourse.backend.service;

import com.drawingcourse.backend.dto.CartAvailabilityStatus;
import com.drawingcourse.backend.dto.CartResponse;
import com.drawingcourse.backend.entity.CartItemType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Display-only Course/Combo Cart projection; it performs trusted reads and no purchase-side writes. */
public final class CourseOnlyCartProjector {
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
        // Courses already paid for by a Combo that is also in the Cart are "covered" (not bought separately).
        CartComboCoverage coverage = CartComboCoverage.evaluate(cart.userId(), cart.items(), combos, ownership,
                pending, clock);
        List<CartResponse.Item> lines = new ArrayList<>(cart.items().size());
        List<CartResponse.AvailabilityWarning> warnings = new ArrayList<>();
        long subtotal = 0;
        for (CartState.Item item : cart.items()) {
            CartResponse.Item line = item.itemType() == CartItemType.COMBO
                    ? projectCombo(item, coverage) : projectCourse(cart, item, coverage);
            if (line.availabilityStatus() == CartAvailabilityStatus.AVAILABLE) {
                subtotal = Math.addExact(subtotal, line.lineTotalVnd());
            }
            lines.add(line);
            // A covered line is informational: it never blocks Checkout.
            if (line.availabilityStatus() != CartAvailabilityStatus.AVAILABLE
                    && line.availabilityStatus() != CartAvailabilityStatus.COVERED) {
                warnings.add(new CartResponse.AvailabilityWarning(item.cartItemId(), line.availabilityStatus(),
                        line.availabilityMessage()));
            }
        }
        boolean anyPayable = lines.stream().anyMatch(l -> l.availabilityStatus() == CartAvailabilityStatus.AVAILABLE);
        return new CartResponse(cart.cartId(), cart.revision(), lines, subtotal, "VND", !warnings.isEmpty(),
                anyPayable && warnings.isEmpty(), warnings);
    }

    private CartResponse.Item projectCourse(CartState cart, CartState.Item item, CartComboCoverage coverage) {
        Optional<CourseCartCatalogProvider.CourseListing> found = courses.findCourse(item.itemId());
        if (found.isEmpty()) {
            return unavailable(item, null, courses.requiresGift(item.itemId())
                    ? CartPolicyMessages.COURSE_GIFT : CartPolicyMessages.COURSE_NOT_FOR_SALE);
        }
        var course = found.get();
        if (!course.active()) return unavailable(item, course.name(), CartPolicyMessages.COURSE_NOT_FOR_SALE);
        if (ownership.isOwnedBy(cart.userId(), item.itemId()))
            return unavailable(item, course.name(), CartPolicyMessages.COURSE_OWNED);
        if (pending.hasActivePendingPurchase(cart.userId(), item.itemId(), clock.instant()))
            return unavailable(item, course.name(), CartPolicyMessages.COURSE_PENDING);
        String coveringCombo = coverage.coveringComboName(item.itemId());
        if (coveringCombo != null) {
            return line(item, course.name(), CartAvailabilityStatus.COVERED, null, null,
                    CartPolicyMessages.courseCoveredByCombo(coveringCombo));
        }
        long total = Math.multiplyExact(course.currentPriceVnd(), item.quantity());
        return line(item, course.name(), CartAvailabilityStatus.AVAILABLE, course.currentPriceVnd(), total, null);
    }

    private CartResponse.Item projectCombo(CartState.Item item, CartComboCoverage coverage) {
        CartComboCoverage.ComboLine evaluated = coverage.comboLine(item.cartItemId());
        String name = evaluated.listing() == null ? null : evaluated.listing().name();
        if (!evaluated.eligible()) return unavailable(item, name, evaluated.unavailableMessage());
        if (evaluated.coveredByComboName() != null) {
            return line(item, name, CartAvailabilityStatus.COVERED, null, null,
                    CartPolicyMessages.comboOverlapsCombo(evaluated.coveredByComboName()));
        }
        long total = Math.multiplyExact(evaluated.listing().currentPriceVnd(), item.quantity());
        return line(item, name, CartAvailabilityStatus.AVAILABLE, evaluated.listing().currentPriceVnd(), total, null);
    }

    private static CartResponse.Item unavailable(CartState.Item item, String name, String reason) {
        return line(item, name, CartAvailabilityStatus.UNAVAILABLE, null, null, reason);
    }

    private static CartResponse.Item line(CartState.Item item, String name, CartAvailabilityStatus status,
                                          Long unitPrice, Long total, String message) {
        return new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(), name, status,
                unitPrice, total, message);
    }
}
