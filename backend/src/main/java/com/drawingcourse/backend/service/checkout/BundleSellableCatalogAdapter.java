package com.drawingcourse.backend.service.checkout;

import com.artacademy.bundle.BundleSellableService;
import com.artacademy.bundle.BundleSnapshot;
import com.artacademy.common.NotFoundException;
import com.drawingcourse.backend.service.BundleSellableCartCatalogAdapter;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bridges Member B's trusted Combo snapshot to D's immutable Checkout offer contract. The snapshot is read ONCE per
 * Checkout; its component list is what C reserves, so a later edit of the Combo cannot change what was bought.
 * Combos whose Courses carry a physical gift are not offered: gift delivery is outside the Course-only flow.
 */
@Component
@ConditionalOnProperty(name = "app.checkout.course-only.catalog-adapter.enabled", havingValue = "true")
public final class BundleSellableCatalogAdapter implements ComboCatalogPort {
    private final BundleSellableService bundles;

    public BundleSellableCatalogAdapter(BundleSellableService bundles) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");
    }

    @Override
    public Optional<ComboOffer> findForCheckout(Integer comboId) {
        if (comboId == null || comboId <= 0) throw new IllegalArgumentException("Combo ID must be a positive Integer");
        final BundleSnapshot snapshot;
        try {
            snapshot = bundles.getSnapshot(comboId);
        } catch (NotFoundException unavailable) {
            return Optional.empty();
        }
        if (snapshot == null || !comboId.equals(snapshot.bundleId())) {
            throw unavailable("Combo catalog returned evidence for a different Combo");
        }
        if (!snapshot.isSellable() || BundleSellableCartCatalogAdapter.hasGift(snapshot)) return Optional.empty();
        if (snapshot.priceVnd() <= 0 || snapshot.title() == null || snapshot.title().isBlank()
                || snapshot.definitionVersion() < 1 || snapshot.courses().size() < 2) {
            throw unavailable("Combo catalog returned invalid evidence");
        }
        var components = snapshot.courses().stream()
                .map(c -> new Component(c.courseId(), c.position())).toList();
        return Optional.of(new ComboOffer(comboId, snapshot.title(), snapshot.priceVnd(),
                snapshot.definitionVersion(), components, true));
    }

    private static CheckoutRejectedException unavailable(String message) {
        return new CheckoutRejectedException(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, message);
    }
}
