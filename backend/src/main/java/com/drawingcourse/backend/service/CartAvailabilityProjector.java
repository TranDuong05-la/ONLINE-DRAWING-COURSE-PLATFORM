package com.drawingcourse.backend.service;

import com.drawingcourse.backend.dto.CartAvailabilityStatus;
import com.drawingcourse.backend.dto.CartResponse;
import com.drawingcourse.backend.entity.CartItemType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds a display-only response from persisted Cart identity/quantity and trusted live providers.
 * It is intentionally not a Spring bean until all provider implementations exist.
 */
public final class CartAvailabilityProjector {
    private static final String PRODUCT_UNAVAILABLE = "Product is unavailable.";
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
        if (combos == null && cart.items().stream().anyMatch(i -> i.itemType() == CartItemType.COMBO)) {
            // Never mistake a Combo ID for a Product ID: without a Combo catalog the Combo cannot be shown.
            throw new CartItemTypeNotSupportedException("Cart cannot project Combo items");
        }
        // Courses already paid for by a Combo that is also in the Cart are "covered" (not bought separately).
        CartComboCoverage coverage = CartComboCoverage.evaluate(cart.userId(), cart.items(), combos, ownership,
                pendingPurchases, clock);
        List<CartResponse.Item> projected = new ArrayList<>(cart.items().size());
        List<CartResponse.AvailabilityWarning> warnings = new ArrayList<>();
        long subtotal = 0;
        for (CartState.Item item : cart.items()) {
            CartResponse.Item line = projectItem(cart.userId(), item, coverage);
            projected.add(line);
            if (line.availabilityStatus() == CartAvailabilityStatus.AVAILABLE) {
                subtotal = Math.addExact(subtotal, Objects.requireNonNull(line.lineTotalVnd()));
            } else if (line.availabilityStatus() != CartAvailabilityStatus.COVERED) {
                // A covered line is informational: it never blocks Checkout.
                warnings.add(new CartResponse.AvailabilityWarning(
                        line.cartItemId(), line.availabilityStatus(), line.availabilityMessage()));
            }
        }
        boolean unavailable = !warnings.isEmpty();
        boolean anyPayable = projected.stream().anyMatch(l -> l.availabilityStatus() == CartAvailabilityStatus.AVAILABLE);
        return new CartResponse(cart.cartId(), cart.revision(), projected, subtotal, "VND", unavailable,
                anyPayable && !unavailable, warnings);
    }

    private CartResponse.Item projectItem(Integer userId, CartState.Item item, CartComboCoverage coverage) {
        if (item.itemType() == CartItemType.COURSE) return projectCourse(userId, item, coverage);
        if (item.itemType() == CartItemType.COMBO) return projectCombo(item, coverage);
        return projectProduct(item);
    }

    private CartResponse.Item projectCombo(CartState.Item item, CartComboCoverage coverage) {
        CartComboCoverage.ComboLine evaluated = coverage.comboLine(item.cartItemId());
        String name = evaluated.listing() == null ? null : evaluated.listing().name();
        if (!evaluated.eligible()) return unavailable(item, name, evaluated.unavailableMessage());
        if (evaluated.coveredByComboName() != null) {
            return new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(), name,
                    CartAvailabilityStatus.COVERED, null, null,
                    CartPolicyMessages.comboOverlapsCombo(evaluated.coveredByComboName()));
        }
        long total = Math.multiplyExact(evaluated.listing().currentPriceVnd(), item.quantity());
        return available(item, name, evaluated.listing().currentPriceVnd(), total);
    }

    private CartResponse.Item projectCourse(Integer userId, CartState.Item item, CartComboCoverage coverage) {
        Optional<CourseCartCatalogProvider.CourseListing> found = courses.findCourse(item.itemId());
        if (found.isEmpty()) {
            return unavailable(item, null, courses.requiresGift(item.itemId())
                    ? CartPolicyMessages.COURSE_GIFT : CartPolicyMessages.COURSE_NOT_FOR_SALE);
        }
        var course = found.get();
        if (!course.active()) return unavailable(item, course.name(), CartPolicyMessages.COURSE_NOT_FOR_SALE);
        if (ownership.isOwnedBy(userId, item.itemId()))
            return unavailable(item, course.name(), CartPolicyMessages.COURSE_OWNED);
        if (pendingPurchases.hasActivePendingPurchase(userId, item.itemId(), clock.instant()))
            return unavailable(item, course.name(), CartPolicyMessages.COURSE_PENDING);
        String coveringCombo = coverage.coveringComboName(item.itemId());
        if (coveringCombo != null) {
            return new CartResponse.Item(item.cartItemId(), item.itemType(), item.itemId(), item.quantity(),
                    course.name(), CartAvailabilityStatus.COVERED, null, null,
                    CartPolicyMessages.courseCoveredByCombo(coveringCombo));
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
