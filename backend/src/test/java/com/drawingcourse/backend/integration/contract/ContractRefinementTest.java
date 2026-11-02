package com.drawingcourse.backend.integration.contract;

import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.CapabilityStatus;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ComboCourseComponent;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ComboSnapshot;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.CourseConsistency;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.CourseConsistencyMode;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.CourseSnapshot;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.IncludedProductKit;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.IncludedProductKits;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ProductConsistency;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ProductSnapshot;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ShippingAttributes;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.UnresolvedIntegrationContractException;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.FulfillmentMode;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.FulfillmentPlan;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.InventoryConfirmationCommand;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.PersistedReservationReference;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ProductOrigin;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ProductRequirement;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.QuoteEvidencePersistence;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ReservationOriginEvidence;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ShippingDestination;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ShippingQuote;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ShippingQuoteCommand;
import com.drawingcourse.backend.integration.contract.LongIdComboCheckoutValidator.FailureCode;
import com.drawingcourse.backend.integration.contract.LongIdComboCheckoutValidator.Invalid;
import com.drawingcourse.backend.integration.contract.LongIdComboCheckoutValidator.SelectedCombo;
import com.drawingcourse.backend.integration.contract.LongIdComboCheckoutValidator.SelectedStandaloneCourse;
import com.drawingcourse.backend.integration.contract.LongIdComboCheckoutValidator.Valid;
import com.drawingcourse.backend.integration.contract.LongIdComboCheckoutValidator.ValidationInput;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContractRefinementTest {
    private static final Instant NOW = Instant.parse("2026-10-30T13:00:00Z");

    @Test
    void courseConsistencyRequiresVersionOrExplicitTransactionalGuarantee() {
        CourseSnapshot versioned = course(1L, CourseConsistency.versioned(7), IncludedProductKits.supported(List.of()));
        CourseSnapshot locked = course(2L, CourseConsistency.transactionLocked(), IncludedProductKits.supported(List.of()));
        CourseSnapshot unknown = course(3L, CourseConsistency.unresolved(), IncludedProductKits.supported(List.of()));

        assertDoesNotThrow(versioned::requireCheckoutReady);
        assertDoesNotThrow(locked::requireCheckoutReady);
        assertThrows(UnresolvedIntegrationContractException.class, unknown::requireCheckoutReady);
        assertThrows(IllegalArgumentException.class,
                () -> new CourseConsistency(CourseConsistencyMode.VERSIONED, OptionalLong.empty()));
        assertThrows(IllegalArgumentException.class, () -> CourseConsistency.versioned(-1));
    }

    @Test
    void confirmationUsesOnlyPersistedReservationEvidenceInDeterministicProductOrder() {
        var mutableOrigins = new ArrayList<>(List.of(new ReservationOriginEvidence(
                ProductOrigin.comboCourseKit(9, 100L, 1), 2)));
        PersistedReservationReference product20 = new PersistedReservationReference("reservation-200", 20L, 2, mutableOrigins);
        mutableOrigins.clear();
        PersistedReservationReference product10 = new PersistedReservationReference("reservation-100", 10L, 3, List.of(
                new ReservationOriginEvidence(ProductOrigin.standaloneProduct(7), 3)));

        InventoryConfirmationCommand command = new InventoryConfirmationCommand(
                1, 2, List.of(product20, product10), NOW);

        assertEquals(List.of(10L, 20L), command.reservations().stream()
                .map(PersistedReservationReference::productId).toList());
        assertEquals(2, product20.origins().getFirst().quantity());
        assertThrows(UnsupportedOperationException.class, () -> product20.origins().clear());
        assertThrows(IllegalArgumentException.class, () -> new PersistedReservationReference(
                "reservation-300", 30L, 2,
                List.of(new ReservationOriginEvidence(ProductOrigin.standaloneProduct(1), 1))));
    }

    @Test
    void digitalOnlyFulfillmentRequiresNoDestinationOrQuote() {
        FulfillmentPlan plan = FulfillmentPlan.fromPhysicalProducts(List.of());

        assertEquals(FulfillmentMode.DIGITAL_ONLY, plan.mode());
        assertFalse(plan.requiresShippingQuote());
        assertThrows(IllegalArgumentException.class,
                () -> new ShippingQuoteCommand(1, plan, destination(), NOW));
    }

    @Test
    void standaloneProductRequiresPhysicalShippingAndDestination() {
        ProductSnapshot product = product(10L);
        var aggregate = InventoryShippingContracts.aggregate(List.of(
                new ProductRequirement(product, 2, ProductOrigin.standaloneProduct(5))));
        FulfillmentPlan plan = FulfillmentPlan.fromPhysicalProducts(aggregate);
        ShippingQuoteCommand command = new ShippingQuoteCommand(1, plan, destination(), NOW);

        assertEquals(FulfillmentMode.PHYSICAL_SHIPMENT, command.fulfillment().mode());
        assertEquals(10L, command.fulfillment().physicalProducts().getFirst().productId());
        assertNotNull(command.destination());
    }

    @Test
    void mandatoryCourseAndComboKitsCannotBeSilentlyOmitted() {
        CourseSnapshot course = course(100L, CourseConsistency.versioned(1),
                IncludedProductKits.supported(List.of(new IncludedProductKit(20L, 2))));
        ProductSnapshot product = product(20L);

        var standalone = InventoryShippingContracts.courseKitRequirements(
                course, 7, Optional.empty(), id -> Optional.of(product));
        var combo = InventoryShippingContracts.courseKitRequirements(
                course, 8, Optional.of(0), id -> Optional.of(product));
        FulfillmentPlan plan = FulfillmentPlan.fromPhysicalProducts(
                InventoryShippingContracts.aggregate(List.of(standalone.getFirst(), combo.getFirst())));

        assertTrue(plan.requiresShippingQuote());
        assertEquals(4, plan.physicalProducts().getFirst().totalQuantity());
        assertThrows(UnresolvedIntegrationContractException.class,
                () -> InventoryShippingContracts.courseKitRequirements(
                        course, 7, Optional.empty(), id -> Optional.empty()));
    }

    @Test
    void shippingQuoteAlwaysHasEvidenceWhilePersistenceMayRemainUnresolved() {
        ShippingQuote unresolved = new ShippingQuote(
                25_000, "quote-1", NOW, QuoteEvidencePersistence.UNRESOLVED);
        ShippingQuote orderPersisted = new ShippingQuote(
                25_000, "quote-2", NOW, QuoteEvidencePersistence.ORDER_SNAPSHOT);

        assertEquals("quote-1", unresolved.quoteReference());
        assertThrows(UnresolvedIntegrationContractException.class, unresolved::requirePersistenceResolved);
        assertDoesNotThrow(orderPersisted::requirePersistenceResolved);
    }

    @Test
    void longIdComboValidatorAcceptsLargeIdsAndPreservesWholeCoverage() {
        long standaloneId = (long) Integer.MAX_VALUE + 100;
        ComboSnapshot combo = combo(9_000_000_000L, OptionalLong.of(4), 8_000_000_002L, 8_000_000_001L);

        Valid result = assertInstanceOf(Valid.class, LongIdComboCheckoutValidator.validate(new ValidationInput(
                List.of(new SelectedStandaloneCourse(standaloneId, 1)),
                List.of(new SelectedCombo(combo, 1)), Set.of(), Set.of())));

        assertEquals(List.of(standaloneId, 8_000_000_001L, 8_000_000_002L).stream().sorted().toList(),
                result.coverage().courseIds());
        assertEquals(9_000_000_000L, combo.comboId());
    }

    @Test
    void longIdComboValidatorRejectsOverlapOwnershipPendingAndInvalidQuantity() {
        ComboSnapshot combo = combo(50L, OptionalLong.of(1), 10L, 20L);

        assertFailure(new ValidationInput(List.of(new SelectedStandaloneCourse(20L, 1)),
                List.of(new SelectedCombo(combo, 1)), Set.of(), Set.of()),
                FailureCode.DUPLICATE_COURSE_COVERAGE);
        assertFailure(new ValidationInput(List.of(), List.of(new SelectedCombo(combo, 1)),
                Set.of(10L), Set.of()), FailureCode.COURSE_ALREADY_OWNED);
        assertFailure(new ValidationInput(List.of(), List.of(new SelectedCombo(combo, 1)),
                Set.of(), Set.of(20L)), FailureCode.COURSE_PENDING_PURCHASE);
        assertFailure(new ValidationInput(List.of(), List.of(new SelectedCombo(combo, 2)),
                Set.of(), Set.of()), FailureCode.INVALID_COMBO_QUANTITY);
    }

    @Test
    void longIdComboValidatorRejectsMissingDefinitionVersion() {
        ComboSnapshot combo = combo(50L, OptionalLong.empty(), 10L, 20L);

        assertFailure(new ValidationInput(List.of(), List.of(new SelectedCombo(combo, 1)),
                Set.of(), Set.of()), FailureCode.COMBO_NOT_CHECKOUT_READY);
    }

    @Test
    void memberBShapedCourseFixturePreservesLongIdsPricesAndMandatoryKitsButFailsUnknownConsistency() {
        long largeCourseId = (long) Integer.MAX_VALUE + 25;
        long largeProductId = (long) Integer.MAX_VALUE + 50;
        MemberBSellableCourse source = new MemberBSellableCourse(largeCourseId, "B Course", "/image",
                500_000, 350_000L, 350_000,
                List.of(new MemberBSellableKit(largeProductId, "KIT-1", "Kit", 2)));

        CourseSnapshot mapped = mapMemberBCourse(source);

        assertEquals(largeCourseId, mapped.courseId());
        assertEquals(350_000, mapped.unitPriceVnd());
        assertEquals(List.of(new IncludedProductKit(largeProductId, 2)), mapped.includedProducts().items());
        assertThrows(UnresolvedIntegrationContractException.class, mapped::requireCheckoutReady);
    }

    @Test
    void memberBShapedBundleFixtureCannotSupplyMissingDefinitionVersion() {
        MemberBSellableCourse course = new MemberBSellableCourse(10L, "B Course", null,
                500_000, null, 500_000, List.of());
        MemberBSellableBundle source = new MemberBSellableBundle(
                5_000_000_000L, "CB-1", "B Bundle", 800_000, 1_000_000, List.of(course));

        ComboSnapshot mapped = mapMemberBBundle(source);

        assertEquals(5_000_000_000L, mapped.comboId());
        assertEquals(800_000, mapped.wholeComboPriceVnd());
        assertTrue(mapped.definitionVersion().isEmpty());
        assertThrows(UnresolvedIntegrationContractException.class, mapped::requireCheckoutReady);
    }

    private static CourseSnapshot mapMemberBCourse(MemberBSellableCourse source) {
        var kits = source.kits().stream()
                .map(value -> new IncludedProductKit(value.productId(), value.quantity())).toList();
        return new CourseSnapshot(source.courseId(), source.title(), source.effectivePrice(), true, true,
                CourseConsistency.unresolved(), IncludedProductKits.supported(kits));
    }

    private static ComboSnapshot mapMemberBBundle(MemberBSellableBundle source) {
        var components = new ArrayList<ComboCourseComponent>();
        for (int index = 0; index < source.courses().size(); index++)
            components.add(new ComboCourseComponent(index, mapMemberBCourse(source.courses().get(index))));
        return new ComboSnapshot(source.bundleId(), source.title(), source.price(),
                OptionalLong.empty(), components, true);
    }

    private static CourseSnapshot course(Long id, CourseConsistency consistency, IncludedProductKits kits) {
        return new CourseSnapshot(id, "Course " + id, 100_000, true, true, consistency, kits);
    }

    private static ComboSnapshot combo(Long comboId, OptionalLong version, Long... courseIds) {
        var components = new ArrayList<ComboCourseComponent>();
        for (int index = 0; index < courseIds.length; index++) {
            components.add(new ComboCourseComponent(index, course(courseIds[index],
                    CourseConsistency.versioned(1), IncludedProductKits.supported(List.of()))));
        }
        return new ComboSnapshot(comboId, "Combo", 200_000, version, components, true);
    }

    private static ProductSnapshot product(Long id) {
        return new ProductSnapshot(id, "Product " + id, 100_000,
                ProductConsistency.versioned(1), true, 100,
                ShippingAttributes.supported(Map.of("class", "standard")));
    }

    private static ShippingDestination destination() {
        return new ShippingDestination("Learner", "0900000000", "1 Main", "HCM", "", "VN");
    }

    private static void assertFailure(ValidationInput input, FailureCode expected) {
        Invalid result = assertInstanceOf(Invalid.class, LongIdComboCheckoutValidator.validate(input));
        assertEquals(expected, result.failure().code());
    }

    /** Fixtures mirror the committed Member B DTO component types without importing Member B source. */
    private record MemberBSellableKit(Long productId, String sku, String name, int quantity) { }

    private record MemberBSellableCourse(Long courseId, String title, String thumbnailUrl,
                                         long price, Long discountPrice, long effectivePrice,
                                         List<MemberBSellableKit> kits) { }

    private record MemberBSellableBundle(Long bundleId, String code, String title, long price,
                                         long originalTotal, List<MemberBSellableCourse> courses) { }
}
