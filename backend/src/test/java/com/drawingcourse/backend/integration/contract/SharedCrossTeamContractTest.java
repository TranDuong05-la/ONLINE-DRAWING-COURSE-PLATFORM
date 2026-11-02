package com.drawingcourse.backend.integration.contract;

import com.drawingcourse.backend.integration.contract.CourseClaimContracts.ClaimLine;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts.ClaimSourceType;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts.EligibilitySnapshot;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts.RefundOrderItemCommand;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts.ReservationCommand;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ComboCourseComponent;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ComboSnapshot;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.CourseConsistency;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.CourseSnapshot;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.IncludedProductKit;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.IncludedProductKits;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ProductConsistency;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ProductSnapshot;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ShippingAttributes;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.UnresolvedIntegrationContractException;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.AggregatedProductRequirement;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.FulfillmentPlan;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.InventoryReservationCommand;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ProductOrigin;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ProductRequirement;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ProductSourceType;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ShippingDestination;
import com.drawingcourse.backend.integration.contract.InventoryShippingContracts.ShippingQuoteCommand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SharedCrossTeamContractTest {
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Test
    void identifierTypesSeparateDOwnedAndCatalogOwnedIdentities() {
        assertEquals(Long.class, componentType(CourseSnapshot.class, "courseId"));
        assertEquals(Long.class, componentType(ComboSnapshot.class, "comboId"));
        assertEquals(Long.class, componentType(ProductSnapshot.class, "productId"));
        assertEquals(Integer.class, componentType(ReservationCommand.class, "authenticatedUserId"));
        assertEquals(Integer.class, componentType(ReservationCommand.class, "orderId"));
        assertEquals(Integer.class, componentType(ClaimLine.class, "orderItemId"));
        assertEquals(Long.class, componentType(ClaimLine.class, "courseId"));
        assertEquals(Integer.class, componentType(ProductOrigin.class, "orderItemId"));
    }

    @Test
    void catalogSnapshotsAreDeeplyImmutable() {
        var mutableKits = new ArrayList<>(List.of(new IncludedProductKit(30L, 2)));
        CourseSnapshot course = course(10L, IncludedProductKits.supported(mutableKits));
        mutableKits.clear();
        assertEquals(List.of(new IncludedProductKit(30L, 2)), course.includedProducts().items());
        assertThrows(UnsupportedOperationException.class,
                () -> course.includedProducts().items().add(new IncludedProductKit(31L, 1)));

        var mutableComponents = new ArrayList<>(List.of(new ComboCourseComponent(0, course)));
        ComboSnapshot combo = combo(OptionalLong.of(4), mutableComponents);
        mutableComponents.clear();
        assertEquals(List.of(10L), combo.orderedComponents().stream()
                .map(component -> component.course().courseId()).toList());

        var mutableAttributes = new HashMap<>(Map.of("shipping-class", "standard"));
        ProductSnapshot product = product(30L, ShippingAttributes.supported(mutableAttributes));
        mutableAttributes.clear();
        assertEquals(Map.of("shipping-class", "standard"), product.shippingAttributes().attributes());
        assertThrows(UnsupportedOperationException.class,
                () -> product.shippingAttributes().attributes().put("weight", "1"));
    }

    @Test
    void comboRejectsDuplicateCourseComponents() {
        CourseSnapshot course = course(10L, IncludedProductKits.supported(List.of()));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> combo(
                OptionalLong.of(1), List.of(new ComboCourseComponent(0, course),
                        new ComboCourseComponent(1, course))));
        assertTrue(failure.getMessage().contains("duplicate Combo Course"));
    }

    @Test
    void comboPreservesStableComponentOrderingAndRequiresContiguousPositions() {
        CourseSnapshot courseB = course(20L, IncludedProductKits.supported(List.of()));
        CourseSnapshot courseA = course(10L, IncludedProductKits.supported(List.of()));
        ComboSnapshot combo = combo(OptionalLong.of(2), List.of(
                new ComboCourseComponent(0, courseB), new ComboCourseComponent(1, courseA)));

        assertEquals(List.of(20L, 10L), combo.orderedComponents().stream()
                .map(component -> component.course().courseId()).toList());
        assertThrows(IllegalArgumentException.class, () -> combo(OptionalLong.of(2), List.of(
                new ComboCourseComponent(1, courseA))));
    }

    @Test
    void missingComboVersionIsRepresentableButFailsClosedForCheckout() {
        ComboSnapshot unresolved = combo(OptionalLong.empty(), List.of(
                new ComboCourseComponent(0, course(10L, IncludedProductKits.supported(List.of())))));

        assertThrows(UnresolvedIntegrationContractException.class, unresolved::requireCheckoutReady);
        assertThrows(IllegalArgumentException.class, () -> combo(OptionalLong.of(-1),
                unresolved.orderedComponents()));
    }

    @Test
    void batchClaimCommandValidatesOriginsDuplicatesAndDeterministicLockOrder() {
        var lines = new ArrayList<>(List.of(
                new ClaimLine(11, 30L, ClaimSourceType.COMBO_COMPONENT, 1),
                new ClaimLine(10, 20L, ClaimSourceType.STANDALONE_COURSE, null)));
        ReservationCommand command = new ReservationCommand(1, 2, lines, NOW, NOW.plusSeconds(900));
        lines.clear();

        assertEquals(2, command.lines().size());
        assertEquals(List.of(20L, 30L), command.courseIdsInLockOrder());
        assertThrows(IllegalArgumentException.class, () -> new ClaimLine(
                10, 20L, ClaimSourceType.COMBO_COMPONENT, null));
        assertThrows(IllegalArgumentException.class, () -> new ReservationCommand(1, 2, List.of(
                new ClaimLine(10, 20L, ClaimSourceType.STANDALONE_COURSE, null),
                new ClaimLine(11, 20L, ClaimSourceType.COMBO_COMPONENT, 0)), NOW, NOW.plusSeconds(1)));
    }

    @Test
    void eligibilityIsAuthoritativeBoundedAndRejectsWholeCheckout() {
        EligibilitySnapshot snapshot = new EligibilitySnapshot(
                new LinkedHashSet<>(List.of(30L, 10L, 20L)), Set.of(20L), Set.of(30L));

        assertEquals(List.of(10L, 20L, 30L), snapshot.requestedCourseIds().stream().toList());
        assertTrue(snapshot.rejectsCheckout());
        assertThrows(IllegalArgumentException.class,
                () -> new EligibilitySnapshot(Set.of(10L), Set.of(20L), Set.of()));
    }

    @Test
    void refundCommandIsOrderItemDrivenAndCarriesIdempotencyIdentity() {
        RefundOrderItemCommand command = new RefundOrderItemCommand(
                1, 2, 3, "refund-operation-1", NOW);

        assertEquals("refund-operation-1", command.refundOperationId());
        assertEquals(NOW, command.refundCompletedAt());
        assertThrows(IllegalArgumentException.class, () -> new RefundOrderItemCommand(1, 2, 3, " ", NOW));
    }

    @Test
    void productKitsRetainOriginAndAggregateDeterministically() {
        ProductSnapshot product20 = product(20L, ShippingAttributes.supported(Map.of("class", "kit")));
        ProductSnapshot product10 = product(10L, ShippingAttributes.supported(Map.of("class", "standalone")));
        CourseSnapshot course = course(100L,
                IncludedProductKits.supported(List.of(new IncludedProductKit(20L, 2))));

        List<ProductRequirement> standaloneKit = InventoryShippingContracts.courseKitRequirements(
                course, 7, Optional.empty(), id -> Optional.of(product20));
        List<ProductRequirement> comboKit = InventoryShippingContracts.courseKitRequirements(
                course, 5, Optional.of(1), id -> Optional.of(product20));
        ProductRequirement standaloneProduct = new ProductRequirement(
                product20, 3, ProductOrigin.standaloneProduct(9));
        ProductRequirement lowerIdProduct = new ProductRequirement(
                product10, 1, ProductOrigin.standaloneProduct(8));

        List<AggregatedProductRequirement> result = InventoryShippingContracts.aggregate(List.of(
                standaloneProduct, comboKit.getFirst(), lowerIdProduct, standaloneKit.getFirst()));

        assertEquals(List.of(10L, 20L), result.stream().map(AggregatedProductRequirement::productId).toList());
        AggregatedProductRequirement aggregated = result.get(1);
        assertEquals(7, aggregated.totalQuantity());
        assertEquals(List.of(ProductSourceType.COMBO_COMPONENT_COURSE_KIT,
                        ProductSourceType.STANDALONE_COURSE_KIT, ProductSourceType.STANDALONE_PRODUCT),
                aggregated.contributions().stream().map(value -> value.origin().sourceType()).toList());
        assertEquals(1, aggregated.contributions().getFirst().origin().comboComponentPosition());
    }

    @Test
    void aggregationRejectsMixedSnapshotsForOneProduct() {
        ProductSnapshot first = product(20L, ShippingAttributes.supported(Map.of("class", "a")));
        ProductSnapshot changed = new ProductSnapshot(20L, "Product 20", 101,
                ProductConsistency.versioned(1), true, 100,
                ShippingAttributes.supported(Map.of("class", "a")));

        assertThrows(IllegalArgumentException.class, () -> InventoryShippingContracts.aggregate(List.of(
                new ProductRequirement(first, 1, ProductOrigin.standaloneProduct(1)),
                new ProductRequirement(changed, 1, ProductOrigin.standaloneProduct(2)))));
    }

    @Test
    void unsupportedKitContractFailsClosed() {
        CourseSnapshot course = course(10L, IncludedProductKits.unsupported());

        assertThrows(UnresolvedIntegrationContractException.class,
                () -> InventoryShippingContracts.courseKitRequirements(
                        course, 1, Optional.empty(), id -> Optional.of(product(id,
                                ShippingAttributes.supported(Map.of())))));
    }

    @Test
    void unresolvedProductConsistencyAndShippingFailClosed() {
        ProductSnapshot unresolvedConsistency = new ProductSnapshot(10L, "Product 10", 100,
                ProductConsistency.unresolved(), true, 10,
                ShippingAttributes.supported(Map.of()));
        ProductSnapshot unsupportedShipping = product(20L, ShippingAttributes.unsupported());

        assertThrows(UnresolvedIntegrationContractException.class,
                () -> unresolvedConsistency.requireCheckoutReady(1));
        var aggregate = InventoryShippingContracts.aggregate(List.of(new ProductRequirement(
                unsupportedShipping, 1, ProductOrigin.standaloneProduct(1))));
        assertThrows(UnresolvedIntegrationContractException.class,
                () -> new InventoryReservationCommand(1, 2, aggregate, NOW.plusSeconds(900)));
        assertThrows(UnresolvedIntegrationContractException.class,
                () -> new ShippingQuoteCommand(1,
                        FulfillmentPlan.fromPhysicalProducts(aggregate), destination(), NOW));
    }

    private static CourseSnapshot course(Long id, IncludedProductKits kits) {
        return new CourseSnapshot(id, "Course " + id, 200, true, true,
                CourseConsistency.versioned(1), kits);
    }

    private static ComboSnapshot combo(OptionalLong version, List<ComboCourseComponent> components) {
        return new ComboSnapshot(50L, "Combo", 300, version, components, true);
    }

    private static ProductSnapshot product(Long id, ShippingAttributes shipping) {
        return new ProductSnapshot(id, "Product " + id, 100,
                ProductConsistency.versioned(1), true, 100, shipping);
    }

    private static ShippingDestination destination() {
        return new ShippingDestination("Learner", "0900000000", "1 Main", "HCM", "", "VN");
    }

    private static Class<?> componentType(Class<?> recordType, String name) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            if (component.getName().equals(name)) return component.getType();
        }
        fail("Missing record component " + name + " on " + recordType.getSimpleName());
        return null;
    }
}
