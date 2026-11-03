package com.drawingcourse.backend.integration.supply;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.artacademy.supply.api.CartProductView;
import com.artacademy.supply.api.SupplyFulfillmentApi;
import com.drawingcourse.backend.repository.OrderShippingAddressRepository;
import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SupplyCheckoutAdapterTest {
    @Test void usesAuthoritativeEProductFieldsAndRejectsIdMismatch() {
        var supply = mock(SupplyFulfillmentApi.class);
        when(supply.findProductForCart(7L)).thenReturn(Optional.of(
                new CartProductView(7L, "sku-7", "Trusted name", 12500, null, 3, true)));
        var adapter = new SupplyProductAdapter(supply);
        var result = adapter.findForCheckout(7).orElseThrow();
        assertEquals("Trusted name", result.name());
        assertEquals(12500, result.unitPriceVnd());
        assertTrue(result.purchasable());

        when(supply.findProductForCart(8L)).thenReturn(Optional.of(
                new CartProductView(8_000_000_000L, "sku-x", "Wrong", 1, null, 1, true)));
        assertThrows(ArithmeticException.class, () -> adapter.findForCheckout(8));
    }

    @Test void reservesAllLinesInOneOrderLevelIdempotentCallInStableOrder() {
        var supply = mock(SupplyFulfillmentApi.class);
        var addresses = mock(OrderShippingAddressRepository.class);
        var adapter = new SupplyInventoryAdapter(supply, addresses);
        adapter.reserveAllForOrder(9, Map.of(20, 2, 3, 1), 41, java.time.Instant.EPOCH);
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(supply).reserveForOrder(eq(41), eq(9), captor.capture());
        var lines = (java.util.List<com.artacademy.supply.api.ReserveLine>) captor.getValue();
        assertEquals(java.util.List.of(3L, 20L), lines.stream().map(l -> l.productId()).toList());
    }

    @Test void shippingFeeIsExplicitAndPhysicalOrderRequiresAddress() {
        var quote = new ConfiguredFlatRateShippingQuote(4500);
        var product = java.util.List.of(new com.drawingcourse.backend.service.checkout.ShippingQuotePort.ProductQuantity(1, 2));
        assertEquals(4500, quote.quoteFeeVnd(4, product, Optional.of(
                new CheckoutRequest.Address("A", "1", "line", "city", "", "VN"))));
        assertThrows(IllegalArgumentException.class, () -> quote.quoteFeeVnd(4, product, Optional.empty()));
    }
}
