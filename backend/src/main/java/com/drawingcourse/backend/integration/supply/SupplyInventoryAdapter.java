package com.drawingcourse.backend.integration.supply;

import com.artacademy.supply.api.ReserveLine;
import com.artacademy.supply.api.ShippingInfo;
import com.artacademy.supply.api.SupplyFulfillmentApi;
import com.drawingcourse.backend.repository.OrderShippingAddressRepository;
import com.drawingcourse.backend.service.checkout.InventoryReservationPort;
import com.drawingcourse.backend.service.expiration.InventoryReservationReleasePort;
import com.drawingcourse.backend.service.settlement.InventoryReservationConfirmationPort;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;

/** Bridges D's order-level checkout/expiry/settlement boundaries to E's local order-idempotent participant. */
@Component
public final class SupplyInventoryAdapter implements InventoryReservationPort,
        InventoryReservationReleasePort, InventoryReservationConfirmationPort {
    private final SupplyFulfillmentApi supply;
    private final OrderShippingAddressRepository addresses;
    public SupplyInventoryAdapter(SupplyFulfillmentApi supply, OrderShippingAddressRepository addresses) {
        this.supply = supply; this.addresses = addresses;
    }
    @Override public void reserveForOrder(Integer userId, Integer productId, int quantity, Integer orderId, Instant expiresAt) {
        reserveAllForOrder(userId, Map.of(productId, quantity), orderId, expiresAt);
    }
    @Override public void reserveAllForOrder(Integer userId, Map<Integer, Integer> quantities, Integer orderId, Instant expiresAt) {
        var lines = quantities.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> ReserveLine.purchase(e.getKey().longValue(), e.getValue())).toList();
        supply.reserveForOrder(orderId, userId, lines);
    }
    @Override public void confirmForOrder(Integer userId, Integer productId, Integer orderId, Instant confirmedAt) {
        confirmProductForOrder(userId, productId, orderId, confirmedAt);
    }
    @Override public void confirmProductForOrder(Integer userId, Integer productId, Integer orderId, Instant confirmedAt) {
        var a = addresses.findByOrder_IdAndOrder_UserId(orderId, userId)
                .orElseThrow(() -> new IllegalStateException("Physical Order has no trusted shipping snapshot")).toSnapshot();
        String address = String.join(", ", a.addressLine(), a.locality(), a.postalCode(), a.countryCode());
        supply.onOrderPaid(orderId, userId, new ShippingInfo(a.recipient(), a.phone(), address));
    }
    @Override public void releaseForOrder(Integer userId, Integer productId, Integer orderId, String reason, Instant releasedAt) {
        supply.releaseForOrder(orderId, reason);
    }
    @Override public void releaseProductForExpiredOrder(Integer userId, Integer productId, Integer orderId, Instant releasedAt) {
        supply.releaseForOrder(orderId, "Order expired");
    }
}
