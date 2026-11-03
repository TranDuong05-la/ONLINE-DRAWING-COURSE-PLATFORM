package com.drawingcourse.backend.integration.supply;

import com.artacademy.supply.api.SupplyFulfillmentApi;
import com.drawingcourse.backend.service.ProductCartCatalogProvider;
import com.drawingcourse.backend.service.ProductStockAvailabilityProvider;
import com.drawingcourse.backend.service.checkout.ProductCatalogPort;
import org.springframework.stereotype.Component;

/** Trusted local projection over E's catalog. D's current INT item identity is checked, never truncated. */
@Component
// Chỉ cần khi bán học cụ (Product Checkout) hoặc bật giỏ hàng chung; chế độ chỉ bán khóa học không dùng kho của E.
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
        "'${app.checkout.product.enabled:false}' == 'true' or '${app.cart.api-enabled:false}' == 'true'")
public final class SupplyProductAdapter implements ProductCatalogPort, ProductCartCatalogProvider,
        ProductStockAvailabilityProvider {
    private final SupplyFulfillmentApi supply;
    public SupplyProductAdapter(SupplyFulfillmentApi supply) { this.supply = supply; }

    @Override public java.util.Optional<ProductOffer> findForCheckout(Integer productId) {
        if (productId == null || productId <= 0) return java.util.Optional.empty();
        return supply.findProductForCart(productId.longValue()).map(view -> {
            if (Math.toIntExact(view.id()) != productId) throw new IllegalStateException("Product identity mismatch");
            return new ProductOffer(productId, view.name(), view.priceVnd(), view.purchasable());
        });
    }

    @Override public java.util.Optional<ProductListing> findProduct(Integer productId) {
        return findForCheckout(productId).map(p -> new ProductListing(p.productId(), p.name(), p.unitPriceVnd(), p.purchasable()));
    }

    @Override public int availableQuantity(Integer productId) {
        if (productId == null || productId <= 0) return 0;
        return supply.findProductForCart(productId.longValue()).map(view -> {
            if (Math.toIntExact(view.id()) != productId) throw new IllegalStateException("Product identity mismatch");
            return view.available();
        }).orElse(0);
    }
}
