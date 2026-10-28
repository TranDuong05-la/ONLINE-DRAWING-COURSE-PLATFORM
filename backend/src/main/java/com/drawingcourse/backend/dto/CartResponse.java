package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.entity.CartItemType;
import java.util.List;
import java.util.Objects;

/** A current, trusted catalog projection. The subtotal is informational, never a checkout quote. */
public record CartResponse(Integer cartId, long revision, List<Item> items, long subtotalVnd, String currency,
                           boolean hasUnavailableItems, boolean canCheckout,
                           List<AvailabilityWarning> availabilityWarnings) {
    public CartResponse {
        items = List.copyOf(items);
        availabilityWarnings = List.copyOf(availabilityWarnings);
        if (cartId != null && cartId <= 0 || revision < 0 || subtotalVnd < 0 || !"VND".equals(currency)) {
            throw new IllegalArgumentException("Cart totals must be non-negative VND");
        }
        long calculatedSubtotal = 0;
        for (Item item : items) {
            if (item.availabilityStatus() == CartAvailabilityStatus.AVAILABLE) {
                calculatedSubtotal = Math.addExact(calculatedSubtotal, Objects.requireNonNull(item.lineTotalVnd()));
            }
        }
        boolean unavailable = !availabilityWarnings.isEmpty();
        if (calculatedSubtotal != subtotalVnd || unavailable != hasUnavailableItems
                || canCheckout != (!items.isEmpty() && !unavailable)) {
            throw new IllegalArgumentException("Cart availability totals are inconsistent");
        }
    }

    public record Item(Integer cartItemId, CartItemType itemType, Integer itemId, int quantity,
                       String displayName, CartAvailabilityStatus availabilityStatus,
                       Long unitPriceVnd, Long lineTotalVnd, String availabilityMessage) {
        public Item {
            Objects.requireNonNull(itemType, "itemType");
            Objects.requireNonNull(availabilityStatus, "availabilityStatus");
            if (cartItemId == null || cartItemId <= 0 || itemId == null || itemId <= 0 || quantity < 1) {
                throw new IllegalArgumentException("Invalid Cart item identity or quantity");
            }
            if (itemType == CartItemType.COURSE && quantity != 1) {
                throw new IllegalArgumentException("Course quantity must be one");
            }
            if (unitPriceVnd != null && unitPriceVnd < 0 || lineTotalVnd != null && lineTotalVnd < 0) {
                throw new IllegalArgumentException("Cart prices cannot be negative");
            }
            if (availabilityStatus == CartAvailabilityStatus.AVAILABLE
                    && (unitPriceVnd == null || lineTotalVnd == null)) {
                throw new IllegalArgumentException("Available Cart items require current prices");
            }
            if (availabilityStatus == CartAvailabilityStatus.UNAVAILABLE
                    && (unitPriceVnd != null || lineTotalVnd != null)) {
                throw new IllegalArgumentException("Unavailable items cannot expose stale prices");
            }
            if (availabilityStatus != CartAvailabilityStatus.AVAILABLE
                    && (availabilityMessage == null || availabilityMessage.isBlank())) {
                throw new IllegalArgumentException("Unavailable items require an explanation");
            }
        }
    }

    public record AvailabilityWarning(Integer cartItemId, CartAvailabilityStatus status, String message) { }
}
