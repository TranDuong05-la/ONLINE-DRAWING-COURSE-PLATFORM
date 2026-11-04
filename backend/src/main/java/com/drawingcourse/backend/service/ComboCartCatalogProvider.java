package com.drawingcourse.backend.service;

import java.util.List;
import java.util.Optional;

/**
 * Read-only B-owned Combo catalog boundary for the Course-only Cart. Empty means the Combo cannot be purchased
 * right now (unknown, inactive, a Course no longer published, or a Course that needs a physical gift).
 * Implementations must not call remote services under a Cart lock.
 */
@FunctionalInterface
public interface ComboCartCatalogProvider {
    Optional<ComboListing> findCombo(Integer comboId);

    /** True when the Combo exists but is not sold here only because one of its Courses carries a physical gift. */
    default boolean requiresGift(Integer comboId) { return false; }

    /** The Combo is ONE purchasable line; courseIds are the Courses whose access it grants. */
    record ComboListing(Integer comboId, String name, long currentPriceVnd, List<Integer> courseIds, boolean active) {
        public ComboListing {
            if (comboId == null || comboId <= 0 || name == null || name.isBlank() || currentPriceVnd < 0
                    || courseIds == null || courseIds.isEmpty()
                    || courseIds.stream().anyMatch(id -> id == null || id <= 0)
                    || courseIds.stream().distinct().count() != courseIds.size()) {
                throw new IllegalArgumentException("Invalid Combo catalog listing");
            }
            courseIds = List.copyOf(courseIds);
        }
    }
}
