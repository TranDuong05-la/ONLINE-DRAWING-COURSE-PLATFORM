package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.CartItemType;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which Courses are already paid for by a Combo that sits in the same Cart. A standalone Course line whose Course is
 * inside an eligible Combo line is "covered": it neither blocks Checkout nor is bought separately, because buying the
 * Combo gives the Course (and after that it shows as already owned). Combos win over standalone Courses regardless of
 * the order they were added; between two Combos that share a Course the earlier one wins.
 */
final class CartComboCoverage {
    /** Evaluation of one Combo line: the catalog listing (null when it cannot be bought), eligibility and cover. */
    record ComboLine(ComboCartCatalogProvider.ComboListing listing, boolean eligible, String coveredByComboName,
                     String unavailableMessage) { }

    private final Map<Integer, ComboLine> comboLines = new LinkedHashMap<>();
    private final Map<Integer, String> courseToComboName = new HashMap<>();

    static CartComboCoverage evaluate(Integer userId, List<CartState.Item> items, ComboCartCatalogProvider combos,
                                      CourseOwnershipProvider ownership, ActivePendingCoursePurchaseProvider pending,
                                      Clock clock) {
        CartComboCoverage coverage = new CartComboCoverage();
        if (combos == null) return coverage;
        for (CartState.Item item : items) {
            if (item.itemType() != CartItemType.COMBO) continue;
            Optional<ComboCartCatalogProvider.ComboListing> found = combos.findCombo(item.itemId());
            if (found.isEmpty()) {
                String why = combos.requiresGift(item.itemId()) ? CartPolicyMessages.COMBO_GIFT
                        : CartPolicyMessages.COMBO_NOT_FOR_SALE;
                coverage.comboLines.put(item.cartItemId(), new ComboLine(null, false, null, why));
                continue;
            }
            var combo = found.get();
            String problem = combo.active() ? null : CartPolicyMessages.COMBO_NOT_FOR_SALE;
            for (Integer courseId : combo.courseIds()) {
                if (problem != null) break;
                if (ownership.isOwnedBy(userId, courseId)) problem = CartPolicyMessages.COMBO_OWNED;
                else if (pending.hasActivePendingPurchase(userId, courseId, clock.instant()))
                    problem = CartPolicyMessages.COMBO_PENDING;
            }
            if (problem != null) {
                coverage.comboLines.put(item.cartItemId(), new ComboLine(combo, false, null, problem));
                continue;
            }
            String earlier = combo.courseIds().stream().map(coverage.courseToComboName::get)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (earlier != null) {
                coverage.comboLines.put(item.cartItemId(), new ComboLine(combo, true, earlier, null));
                continue;
            }
            combo.courseIds().forEach(id -> coverage.courseToComboName.put(id, combo.name()));
            coverage.comboLines.put(item.cartItemId(), new ComboLine(combo, true, null, null));
        }
        return coverage;
    }

    ComboLine comboLine(Integer cartItemId) { return comboLines.get(cartItemId); }

    /** Name of the Combo in the Cart that covers this Course, or null. */
    String coveringComboName(Integer courseId) { return courseToComboName.get(courseId); }
}
