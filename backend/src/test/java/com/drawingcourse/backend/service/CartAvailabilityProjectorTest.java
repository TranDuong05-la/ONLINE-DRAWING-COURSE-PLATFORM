package com.drawingcourse.backend.service;

import com.drawingcourse.backend.dto.CartAvailabilityStatus;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.entity.OrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartAvailabilityProjectorTest {
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private final CourseCartCatalogProvider courses = mock(CourseCartCatalogProvider.class);
    private final CourseOwnershipProvider ownership = mock(CourseOwnershipProvider.class);
    private final ActivePendingCoursePurchaseProvider pending = mock(ActivePendingCoursePurchaseProvider.class);
    private final ProductCartCatalogProvider products = mock(ProductCartCatalogProvider.class);
    private final ProductStockAvailabilityProvider stock = mock(ProductStockAvailabilityProvider.class);
    private final CartAvailabilityProjector projector = new CartAvailabilityProjector(
            courses, ownership, pending, products, stock, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void subtotalIncludesOnlyAvailableLinesAndUnavailableItemRemainsVisibleWithoutPrice() {
        when(courses.findCourse(4)).thenReturn(Optional.empty());
        when(products.findProduct(8)).thenReturn(Optional.of(
                new ProductCartCatalogProvider.ProductListing(8, "Brush", 120_000, true)));
        when(stock.availableQuantity(8)).thenReturn(3);
        var response = projector.project(new CartState(10, 7, java.util.List.of(
                new CartState.Item(21, CartItemType.COURSE, 4, 1),
                new CartState.Item(22, CartItemType.PRODUCT, 8, 2))));

        assertEquals(240_000, response.subtotalVnd());
        assertTrue(response.hasUnavailableItems());
        assertFalse(response.canCheckout());
        assertEquals(2, response.items().size());
        var missing = response.items().getFirst();
        assertEquals(CartAvailabilityStatus.UNAVAILABLE, missing.availabilityStatus());
        assertNull(missing.unitPriceVnd());
        assertNull(missing.lineTotalVnd());
        assertEquals(CartAvailabilityStatus.AVAILABLE, response.items().getLast().availabilityStatus());
        verify(stock).availableQuantity(8); // missing Course is not treated as stock data
    }

    @Test void outOfStockProductStaysInCartAndDoesNotContributeToSubtotalOrReserveStock() {
        when(products.findProduct(8)).thenReturn(Optional.of(
                new ProductCartCatalogProvider.ProductListing(8, "Brush", 120_000, true)));
        when(stock.availableQuantity(8)).thenReturn(1);
        var response = projector.project(new CartState(10, 7,
                java.util.List.of(new CartState.Item(22, CartItemType.PRODUCT, 8, 2))));

        assertEquals(1, response.items().size());
        assertEquals(CartAvailabilityStatus.OUT_OF_STOCK, response.items().getFirst().availabilityStatus());
        assertEquals(120_000L, response.items().getFirst().unitPriceVnd());
        assertEquals(240_000L, response.items().getFirst().lineTotalVnd());
        assertEquals(0, response.subtotalVnd());
        assertTrue(response.hasUnavailableItems());
        assertFalse(response.canCheckout());
        verify(stock).availableQuantity(8);
        // The stock port has no reservation or mutation method by design.
    }

    @Test void courseOwnershipAndActivePendingPurchaseMakeLineUnavailableWithoutLeakingPrice() {
        when(courses.findCourse(4)).thenReturn(Optional.of(
                new CourseCartCatalogProvider.CourseListing(4, "Portrait", 900_000, true)));
        when(ownership.isOwnedBy(7, 4)).thenReturn(false);
        when(pending.hasActivePendingPurchase(7, 4, NOW)).thenReturn(true);
        var response = projector.project(new CartState(10, 7,
                java.util.List.of(new CartState.Item(21, CartItemType.COURSE, 4, 1))));

        assertEquals(CartAvailabilityStatus.UNAVAILABLE, response.items().getFirst().availabilityStatus());
        assertNull(response.items().getFirst().unitPriceVnd());
        assertEquals(0, response.subtotalVnd());
        assertFalse(response.canCheckout());
        verify(pending).hasActivePendingPurchase(7, 4, NOW);
    }

    @Test void pendingPurchasePolicyUsesOrderStatusAndExclusiveImmutableDeadline() {
        Instant deadline = NOW.plusSeconds(30);
        assertTrue(ActivePendingCoursePurchasePolicy.isActive(OrderStatus.PENDING_PAYMENT, deadline, NOW));
        assertFalse(ActivePendingCoursePurchasePolicy.isActive(OrderStatus.PENDING_PAYMENT, deadline, deadline));
        assertFalse(ActivePendingCoursePurchasePolicy.isActive(OrderStatus.CANCELLED, deadline, NOW));
        assertFalse(ActivePendingCoursePurchasePolicy.isActive(OrderStatus.EXPIRED, deadline, NOW));
        assertFalse(ActivePendingCoursePurchasePolicy.isActive(OrderStatus.COMPLETED, deadline, NOW));
    }

    @Test void emptyCartAndOverflowAreHandledWithoutInventingPrices() {
        var empty = projector.project(new CartState(null, 7, java.util.List.of()));
        assertTrue(empty.items().isEmpty());
        assertEquals(0, empty.subtotalVnd());
        assertFalse(empty.canCheckout());

        when(products.findProduct(9)).thenReturn(Optional.of(new ProductCartCatalogProvider.ProductListing(
                9, "Large total", Long.MAX_VALUE, true)));
        when(stock.availableQuantity(9)).thenReturn(Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> projector.project(new CartState(10, 7,
                java.util.List.of(new CartState.Item(23, CartItemType.PRODUCT, 9, 2)))));
    }
}
