package com.drawingcourse.backend.integration.supply;

import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import com.drawingcourse.backend.service.checkout.ShippingQuotePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

/** Explicit demo policy: a configured flat VND fee per physical Order, independent of line count. */
@Component
@ConditionalOnProperty(name = "app.checkout.product.enabled", havingValue = "true")
public final class ConfiguredFlatRateShippingQuote implements ShippingQuotePort {
    private final long feeVnd;
    public ConfiguredFlatRateShippingQuote(@Value("${app.checkout.shipping.flat-fee-vnd}") long feeVnd) {
        if (feeVnd < 0) throw new IllegalArgumentException("Configured shipping fee must be nonnegative");
        this.feeVnd = feeVnd;
    }
    @Override public long quoteFeeVnd(Integer userId, List<ProductQuantity> products, Optional<CheckoutRequest.Address> address) {
        if (userId == null || products == null || products.isEmpty() || address == null || address.isEmpty())
            throw new IllegalArgumentException("Physical Order requires a shipping address and Product lines");
        return feeVnd;
    }
}
