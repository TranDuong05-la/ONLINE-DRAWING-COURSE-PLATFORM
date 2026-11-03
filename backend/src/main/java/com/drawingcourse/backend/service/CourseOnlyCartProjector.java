package com.drawingcourse.backend.service;

import com.drawingcourse.backend.dto.CartAvailabilityStatus;
import com.drawingcourse.backend.dto.CartResponse;
import com.drawingcourse.backend.entity.CartItemType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Display-only Course Cart projection; it performs trusted reads and no purchase-side writes. */
public final class CourseOnlyCartProjector {
    private static final String UNAVAILABLE = "Course is unavailable or not eligible for this account.";
    private final CourseCartCatalogProvider courses;
    private final CourseOwnershipProvider ownership;
    private final ActivePendingCoursePurchaseProvider pending;
    private final Clock clock;

    public CourseOnlyCartProjector(CourseCartCatalogProvider courses, CourseOwnershipProvider ownership,
            ActivePendingCoursePurchaseProvider pending, Clock clock) {
        this.courses = Objects.requireNonNull(courses);
        this.ownership = Objects.requireNonNull(ownership);
        this.pending = Objects.requireNonNull(pending);
        this.clock = Objects.requireNonNull(clock);
    }

    public CartResponse project(CartState cart) {
        Objects.requireNonNull(cart, "cart");
        if (cart.items().stream().anyMatch(item -> item.itemType() != CartItemType.COURSE)) {
            throw new CartItemTypeNotSupportedException("Course-only Cart cannot project Product items");
        }
        List<CartResponse.Item> lines = new ArrayList<>(cart.items().size());
        List<CartResponse.AvailabilityWarning> warnings = new ArrayList<>();
        long subtotal = 0;
        for (CartState.Item item : cart.items()) {
            Optional<CourseCartCatalogProvider.CourseListing> found = courses.findCourse(item.itemId());
            CartResponse.Item line;
            if (found.isEmpty()) {
                line = unavailable(item, null);
            } else {
                var course = found.get();
                boolean eligible = course.active() && !ownership.isOwnedBy(cart.userId(), item.itemId())
                        && !pending.hasActivePendingPurchase(cart.userId(), item.itemId(), clock.instant());
                if (!eligible) {
                    line = unavailable(item, course.name());
                } else {
                    long total = Math.multiplyExact(course.currentPriceVnd(), item.quantity());
                    line = new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(),
                            course.name(), CartAvailabilityStatus.AVAILABLE, course.currentPriceVnd(), total, null);
                    subtotal = Math.addExact(subtotal, total);
                }
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

    private static CartResponse.Item unavailable(CartState.Item item, String name) {
        return new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(), name,
                CartAvailabilityStatus.UNAVAILABLE, null, null, UNAVAILABLE);
    }
}
