package com.drawingcourse.backend.service;

import com.artacademy.bundle.BundleSellableService;
import com.artacademy.bundle.BundleSnapshot;
import com.artacademy.common.NotFoundException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;

/**
 * Read-only Cart projection of Member B's trusted Combo catalog.
 * A Combo is offered only when B says it is sellable AND none of its Courses carries a physical gift: gift delivery
 * (address, stock) is not part of the Course-only purchase flow, so such Combos stay unavailable, like single Courses.
 */
public final class BundleSellableCartCatalogAdapter implements ComboCartCatalogProvider {
    private final BundleSellableService bundles;

    public BundleSellableCartCatalogAdapter(BundleSellableService bundles) {
        this.bundles = Objects.requireNonNull(bundles);
    }

    @Override
    public Optional<ComboListing> findCombo(Integer comboId) {
        if (comboId == null || comboId <= 0) throw new IllegalArgumentException("Combo ID must be positive");
        final BundleSnapshot snapshot;
        try {
            snapshot = bundles.getSnapshot(comboId);
        } catch (NotFoundException unavailable) {
            return Optional.empty();
        } catch (DataAccessException failure) {
            throw new CartCatalogUnavailableException("Combo catalog could not be read", failure);
        }
        if (snapshot == null) throw new CartCatalogUnavailableException("Combo catalog returned no evidence");
        if (!Objects.equals(snapshot.bundleId(), comboId))
            throw new CartCatalogUnavailableException("Combo catalog returned a mismatched ID");
        if (!snapshot.isSellable() || hasGift(snapshot)) return Optional.empty();
        if (snapshot.priceVnd() <= 0 || snapshot.title() == null || snapshot.title().isBlank()
                || snapshot.courses().size() < 2)
            throw new CartCatalogUnavailableException("Combo catalog returned invalid evidence");
        return Optional.of(new ComboListing(comboId, snapshot.title(), snapshot.priceVnd(), snapshot.courseIds(), true));
    }

    /** Any configured gift (available or not) means physical fulfillment, which this flow cannot do. */
    public static boolean hasGift(BundleSnapshot snapshot) {
        return snapshot.courses().stream()
                .anyMatch(course -> !course.kits().isEmpty() || !course.unavailableKits().isEmpty());
    }
}
