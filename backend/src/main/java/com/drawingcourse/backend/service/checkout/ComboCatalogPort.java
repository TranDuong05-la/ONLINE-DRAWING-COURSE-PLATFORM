package com.drawingcourse.backend.service.checkout;

import java.util.List;
import java.util.Optional;

/**
 * B-owned trusted local Combo catalog read for Checkout. Never implement with a remote call inside Checkout's
 * transaction. Empty means the Combo is not purchasable now (unknown, inactive, unpublished Course, gift required).
 */
@FunctionalInterface
public interface ComboCatalogPort {
    Optional<ComboOffer> findForCheckout(Integer comboId);

    /**
     * Immutable offer read once per Checkout: the Combo is ONE Order line priced at {@code unitPriceVnd}.
     * {@code components} are in B's order; {@code position} is B's 1-based position of the Course in the Combo.
     */
    record ComboOffer(Integer comboId, String name, long unitPriceVnd, int definitionVersion,
                      List<Component> components, boolean purchasable) {
        public ComboOffer {
            if (comboId == null || comboId <= 0 || name == null || name.isBlank() || unitPriceVnd < 0
                    || definitionVersion < 1 || components == null || components.isEmpty()) {
                throw new IllegalArgumentException("Invalid Combo offer");
            }
            components = List.copyOf(components);
        }
    }

    record Component(Integer courseId, int position) {
        public Component {
            if (courseId == null || courseId <= 0 || position < 1)
                throw new IllegalArgumentException("Invalid Combo component");
        }
    }
}
