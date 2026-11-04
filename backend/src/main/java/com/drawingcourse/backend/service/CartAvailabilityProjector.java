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

/**
 * Builds a display-only response from persisted Cart identity/quantity and trusted live providers.
 * It is intentionally not a Spring bean until all provider implementations exist.
 */
public final class CartAvailabilityProjector {
    private static final String COURSE_UNAVAILABLE = "Course is unavailable or not eligible for this account.";
    private static final String PRODUCT_UNAVAILABLE = "Product is unavailable.";
    private static final String COMBO_UNAVAILABLE = "Combo is unavailable or not eligible for this account.";
    private static final String COMBO_OVERLAP =
            "This item covers a Course that is already in your Cart. Remove one of them.";
    private static final String OUT_OF_STOCK = "Insufficient stock for the Cart quantity.";

    private final CourseCartCatalogProvider courses;
    private final CourseOwnershipProvider ownership;
    private final ActivePendingCoursePurchaseProvider pendingPurchases;
    private final ProductCartCatalogProvider products;
    private final ProductStockAvailabilityProvider stock;
    private final Clock clock;
    /** Null when Combos are not part of this deployment. */
    private final ComboCartCatalogProvider combos;

    public CartAvailabilityProjector(CourseCartCatalogProvider courses, CourseOwnershipProvider ownership,
            ActivePendingCoursePurchaseProvider pendingPurchases, ProductCartCatalogProvider products,
            ProductStockAvailabilityProvider stock, Clock clock) {
        this(courses, ownership, pendingPurchases, products, stock, clock, null);
    }

    public CartAvailabilityProjector(CourseCartCatalogProvider courses, CourseOwnershipProvider ownership,
            ActivePendingCoursePurchaseProvider pendingPurchases, ProductCartCatalogProvider products,
            ProductStockAvailabilityProvider stock, Clock clock, ComboCartCatalogProvider combos) {
        this.combos = combos;
        this.courses = Objects.requireNonNull(courses);
        this.ownership = Objects.requireNonNull(ownership);
        this.pendingPurchases = Objects.requireNonNull(pendingPurchases);
        this.products = Objects.requireNonNull(products);
        this.stock = Objects.requireNonNull(stock);
        this.clock = Objects.requireNonNull(clock);
    }

    public CartResponse project(CartState cart) {
        Objects.requireNonNull(cart, "cart");
        List<CartResponse.Item> projected = new ArrayList<>(cart.items().size());
        List<CartResponse.AvailabilityWarning> warnings = new ArrayList<>();
        long subtotal = 0;
        // Courses already covered by an earlier line (standalone or inside a Combo): a later line covering the same
        // Course cannot be bought together with it (checkout rejects duplicate coverage).
        Set<Integer> covered = new HashSet<>();
        for (CartState.Item item : cart.items()) {
            CartResponse.Item line = projectItem(cart.userId(), item, covered);
            projected.add(line);
            if (line.availabilityStatus() == CartAvailabilityStatus.AVAILABLE) {
                subtotal = Math.addExact(subtotal, Objects.requireNonNull(line.lineTotalVnd()));
            } else {
                warnings.add(new CartResponse.AvailabilityWarning(
                        line.cartItemId(), line.availabilityStatus(), line.availabilityMessage()));
            }
        }
        boolean unavailable = !warnings.isEmpty();
        return new CartResponse(cart.cartId(), cart.revision(), projected, subtotal, "VND", unavailable,
                !projected.isEmpty() && !unavailable, warnings);
    }

    private CartResponse.Item projectItem(Integer userId, CartState.Item item, Set<Integer> covered) {
        if (item.itemType() == CartItemType.COURSE) {
            CartResponse.Item line = projectCourse(userId, item);
            if (line.availabilityStatus() == CartAvailabilityStatus.AVAILABLE && !covered.add(item.itemId()))
                return unavailable(item, line.displayName(), COMBO_OVERLAP);
            return line;
        }
        if (item.itemType() == CartItemType.COMBO) {
            // Never mistake a Combo ID for a Product ID: without a Combo catalog the Combo cannot be shown.
            if (combos == null) throw new CartItemTypeNotSupportedException("Cart cannot project Combo items");
            return projectCombo(userId, item, covered);
        }
        return projectProduct(item);
    }

    private CartResponse.Item projectCombo(Integer userId, CartState.Item item, Set<Integer> covered) {
        Optional<ComboCartCatalogProvider.ComboListing> found = combos.findCombo(item.itemId());
        if (found.isEmpty()) return unavailable(item, null, COMBO_UNAVAILABLE);
        var combo = found.get();
        boolean eligible = combo.active();
        for (Integer courseId : combo.courseIds()) {
            if (!eligible) break;
            eligible = !ownership.isOwnedBy(userId, courseId)
                    && !pendingPurchases.hasActivePendingPurchase(userId, courseId, clock.instant());
        }
        if (!eligible) return unavailable(item, combo.name(), COMBO_UNAVAILABLE);
        if (combo.courseIds().stream().anyMatch(covered::contains))
            return unavailable(item, combo.name(), COMBO_OVERLAP);
        covered.addAll(combo.courseIds());
        long total = Math.multiplyExact(combo.currentPriceVnd(), item.quantity());
        return available(item, combo.name(), combo.currentPriceVnd(), total);
    }

    private CartResponse.Item projectCourse(Integer userId, CartState.Item item) {
        Optional<CourseCartCatalogProvider.CourseListing> found = courses.findCourse(item.itemId());
        if (found.isEmpty()) return unavailable(item, null, COURSE_UNAVAILABLE);
        var course = found.get();
        if (!course.active() || ownership.isOwnedBy(userId, item.itemId())
                || pendingPurchases.hasActivePendingPurchase(userId, item.itemId(), clock.instant())) {
            return unavailable(item, course.name(), COURSE_UNAVAILABLE);
        }
        long total = Math.multiplyExact(course.currentPriceVnd(), item.quantity());
        return available(item, course.name(), course.currentPriceVnd(), total);
    }

    private CartResponse.Item projectProduct(CartState.Item item) {
        Optional<ProductCartCatalogProvider.ProductListing> found = products.findProduct(item.itemId());
        if (found.isEmpty()) return unavailable(item, null, PRODUCT_UNAVAILABLE);
        var product = found.get();
        if (!product.active()) return unavailable(item, product.name(), PRODUCT_UNAVAILABLE);
        long total = Math.multiplyExact(product.currentPriceVnd(), item.quantity());
        int available = stock.availableQuantity(item.itemId());
        if (available < 0) throw new IllegalStateException("Stock provider returned a negative quantity");
        if (available < item.quantity()) {
            return new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(),
                    product.name(), CartAvailabilityStatus.OUT_OF_STOCK, product.currentPriceVnd(), total,
                    OUT_OF_STOCK);
        }
        return available(item, product.name(), product.currentPriceVnd(), total);
    }

    private static CartResponse.Item available(CartState.Item item, String name, long price, long total) {
        return new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(), name,
                CartAvailabilityStatus.AVAILABLE, price, total, null);
    }

    private static CartResponse.Item unavailable(CartState.Item item, String name, String reason) {
        return new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(), name,
                CartAvailabilityStatus.UNAVAILABLE, null, null, reason);
    }
}
