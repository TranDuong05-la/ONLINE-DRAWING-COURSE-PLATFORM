package com.drawingcourse.backend.integration.contract;

import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.CourseSnapshot;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.IncludedProductKit;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.ProductSnapshot;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.TrustedProductCatalog;
import com.drawingcourse.backend.integration.contract.CrossTeamCatalogContracts.UnresolvedIntegrationContractException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.TreeMap;

/** Pure E-owned inventory and shipping contract shapes. No inventory or shipping implementation is provided. */
public final class InventoryShippingContracts {
    private InventoryShippingContracts() { }

    /** Production mutations must join the caller's transaction and be Order-idempotent. */
    public interface InventoryReservations {
        void reserve(InventoryReservationCommand command);

        void confirm(InventoryConfirmationCommand command);

        void release(InventoryReleaseCommand command);
    }

    @FunctionalInterface
    public interface LocalShippingQuote {
        ShippingQuote quote(ShippingQuoteCommand command);
    }

    public enum ProductSourceType {
        STANDALONE_PRODUCT,
        STANDALONE_COURSE_KIT,
        COMBO_COMPONENT_COURSE_KIT
    }

    public enum TerminalUnpaidReason { EXPIRED, CANCELLED, FINAL_PAYMENT_FAILURE }

    public enum FulfillmentMode { DIGITAL_ONLY, PHYSICAL_SHIPMENT }

    /** Quote evidence is mandatory; its future durable storage owner remains an explicit team decision. */
    public enum QuoteEvidencePersistence { ORDER_SNAPSHOT, INVENTORY_RESERVATION, UNRESOLVED }

    /** Origin is retained even when multiple contributions aggregate into one deterministically locked Product. */
    public record ProductOrigin(ProductSourceType sourceType, Integer orderItemId,
                                Long sourceCourseId, Integer comboComponentPosition) {
        public ProductOrigin {
            Objects.requireNonNull(sourceType, "sourceType");
            requirePositive(orderItemId, "orderItemId");
            switch (sourceType) {
                case STANDALONE_PRODUCT -> {
                    if (sourceCourseId != null || comboComponentPosition != null)
                        throw new IllegalArgumentException("standalone Product cannot have Course coordinates");
                }
                case STANDALONE_COURSE_KIT -> {
                    requirePositive(sourceCourseId, "sourceCourseId");
                    if (comboComponentPosition != null)
                        throw new IllegalArgumentException("standalone Course kit cannot have Combo position");
                }
                case COMBO_COMPONENT_COURSE_KIT -> {
                    requirePositive(sourceCourseId, "sourceCourseId");
                    if (comboComponentPosition == null || comboComponentPosition < 0)
                        throw new IllegalArgumentException("Combo Course kit requires component position");
                }
            }
        }

        public static ProductOrigin standaloneProduct(Integer orderItemId) {
            return new ProductOrigin(ProductSourceType.STANDALONE_PRODUCT, orderItemId, null, null);
        }

        public static ProductOrigin standaloneCourseKit(Integer orderItemId, Long courseId) {
            return new ProductOrigin(ProductSourceType.STANDALONE_COURSE_KIT, orderItemId, courseId, null);
        }

        public static ProductOrigin comboCourseKit(Integer orderItemId, Long courseId, int componentPosition) {
            return new ProductOrigin(ProductSourceType.COMBO_COMPONENT_COURSE_KIT,
                    orderItemId, courseId, componentPosition);
        }
    }

    public record ProductRequirement(ProductSnapshot product, int quantity, ProductOrigin origin) {
        public ProductRequirement {
            Objects.requireNonNull(product, "product");
            if (quantity <= 0) throw new IllegalArgumentException("Product quantity must be positive");
            Objects.requireNonNull(origin, "origin");
        }
    }

    public record AggregatedProductRequirement(ProductSnapshot product, int totalQuantity,
                                               List<ProductRequirement> contributions) {
        public AggregatedProductRequirement {
            Objects.requireNonNull(product, "product");
            if (totalQuantity <= 0) throw new IllegalArgumentException("total quantity must be positive");
            contributions = immutableRequirements(contributions);
            int calculated = 0;
            for (ProductRequirement contribution : contributions) {
                if (!product.equals(contribution.product()))
                    throw new IllegalArgumentException("mixed Product snapshots in one aggregate");
                calculated = Math.addExact(calculated, contribution.quantity());
            }
            if (calculated != totalQuantity)
                throw new IllegalArgumentException("aggregate quantity does not match contributions");
        }

        public Long productId() { return product.productId(); }
    }

    /**
     * Resolves a supported Course kit contract through the authoritative Product catalog. Missing or malformed
     * Product snapshots fail closed. No remote lookup is authorized by this utility.
     */
    public static List<ProductRequirement> courseKitRequirements(
            CourseSnapshot course, Integer orderItemId, Optional<Integer> comboComponentPosition,
            TrustedProductCatalog products) {
        Objects.requireNonNull(course, "course");
        requirePositive(orderItemId, "orderItemId");
        Objects.requireNonNull(comboComponentPosition, "comboComponentPosition");
        Objects.requireNonNull(products, "products");
        List<IncludedProductKit> kits = course.includedProducts().requireSupported();
        var result = new ArrayList<ProductRequirement>(kits.size());
        for (IncludedProductKit kit : kits) {
            ProductSnapshot product = products.findProduct(kit.productId()).orElseThrow(() ->
                    new UnresolvedIntegrationContractException("included Product is unavailable: " + kit.productId()));
            if (!kit.productId().equals(product.productId()))
                throw new IllegalArgumentException("Product catalog returned a mismatched identity");
            ProductOrigin origin = comboComponentPosition
                    .map(position -> ProductOrigin.comboCourseKit(orderItemId, course.courseId(), position))
                    .orElseGet(() -> ProductOrigin.standaloneCourseKit(orderItemId, course.courseId()));
            result.add(new ProductRequirement(product, kit.quantity(), origin));
        }
        return List.copyOf(result);
    }

    /** Sorted by Product ID; contribution order is canonical and retains every source. */
    public static List<AggregatedProductRequirement> aggregate(List<ProductRequirement> requirements) {
        List<ProductRequirement> immutable = immutableRequirements(requirements);
        if (immutable.isEmpty()) return List.of();
        var snapshots = new HashMap<Long, ProductSnapshot>();
        var grouped = new TreeMap<Long, List<ProductRequirement>>();
        for (ProductRequirement requirement : immutable) {
            Long id = requirement.product().productId();
            ProductSnapshot first = snapshots.putIfAbsent(id, requirement.product());
            if (first != null && !first.equals(requirement.product()))
                throw new IllegalArgumentException("inconsistent trusted Product snapshots for " + id);
            grouped.computeIfAbsent(id, ignored -> new ArrayList<>()).add(requirement);
        }

        Comparator<ProductRequirement> contributionOrder = Comparator
                .comparing((ProductRequirement value) -> value.origin().orderItemId())
                .thenComparing(value -> value.origin().sourceType().ordinal())
                .thenComparing(value -> value.origin().sourceCourseId(), Comparator.nullsFirst(Long::compareTo))
                .thenComparing(value -> value.origin().comboComponentPosition(), Comparator.nullsFirst(Integer::compareTo));
        var result = new ArrayList<AggregatedProductRequirement>(grouped.size());
        grouped.forEach((productId, contributions) -> {
            contributions.sort(contributionOrder);
            int total = 0;
            for (ProductRequirement contribution : contributions)
                total = Math.addExact(total, contribution.quantity());
            result.add(new AggregatedProductRequirement(snapshots.get(productId), total, contributions));
        });
        return List.copyOf(result);
    }

    public record InventoryReservationCommand(Integer authenticatedUserId, Integer orderId,
                                              List<AggregatedProductRequirement> products,
                                              Instant expiresAt) {
        public InventoryReservationCommand {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            requirePositive(orderId, "orderId");
            products = canonicalAggregates(products);
            Objects.requireNonNull(expiresAt, "expiresAt");
            products.forEach(item -> item.product().requireCheckoutReady(item.totalQuantity()));
        }
    }

    public record ReservationOriginEvidence(ProductOrigin origin, int quantity) {
        public ReservationOriginEvidence {
            Objects.requireNonNull(origin, "origin");
            if (quantity <= 0) throw new IllegalArgumentException("origin quantity must be positive");
        }
    }

    /** Durable E-owned evidence created by reservation; it contains no current catalog price/stock/shipping data. */
    public record PersistedReservationReference(String reservationReference, Long productId, int totalQuantity,
                                                List<ReservationOriginEvidence> origins) {
        public PersistedReservationReference {
            reservationReference = requireReference(reservationReference, "reservationReference");
            requirePositive(productId, "productId");
            if (totalQuantity <= 0) throw new IllegalArgumentException("reservation quantity must be positive");
            Objects.requireNonNull(origins, "origins");
            var copy = new ArrayList<ReservationOriginEvidence>(origins.size());
            int calculated = 0;
            for (ReservationOriginEvidence origin : origins) {
                ReservationOriginEvidence required = Objects.requireNonNull(origin, "origin evidence");
                calculated = Math.addExact(calculated, required.quantity());
                copy.add(required);
            }
            if (copy.isEmpty() || calculated != totalQuantity)
                throw new IllegalArgumentException("persisted origin evidence must cover the reservation quantity");
            origins = List.copyOf(copy);
        }
    }

    public record InventoryConfirmationCommand(Integer authenticatedUserId, Integer orderId,
                                               List<PersistedReservationReference> reservations,
                                               Instant confirmedAt) {
        public InventoryConfirmationCommand {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            requirePositive(orderId, "orderId");
            reservations = canonicalReservations(reservations);
            Objects.requireNonNull(confirmedAt, "confirmedAt");
        }
    }

    public record InventoryReleaseCommand(Integer authenticatedUserId, Integer orderId,
                                          List<PersistedReservationReference> reservations,
                                          TerminalUnpaidReason reason, Instant releasedAt) {
        public InventoryReleaseCommand {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            requirePositive(orderId, "orderId");
            reservations = canonicalReservations(reservations);
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(releasedAt, "releasedAt");
        }
    }

    /** Digital orders have no physical requirements; every Product/mandatory kit makes fulfillment physical. */
    public record FulfillmentPlan(FulfillmentMode mode, List<AggregatedProductRequirement> physicalProducts) {
        public FulfillmentPlan {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(physicalProducts, "physicalProducts");
            if (mode == FulfillmentMode.DIGITAL_ONLY) {
                if (!physicalProducts.isEmpty())
                    throw new IllegalArgumentException("digital-only fulfillment cannot contain Products");
                physicalProducts = List.of();
            } else {
                physicalProducts = canonicalAggregates(physicalProducts);
                physicalProducts.forEach(item -> item.product().shippingAttributes().requireSupported());
            }
        }

        public static FulfillmentPlan fromPhysicalProducts(List<AggregatedProductRequirement> products) {
            Objects.requireNonNull(products, "products");
            return products.isEmpty()
                    ? new FulfillmentPlan(FulfillmentMode.DIGITAL_ONLY, List.of())
                    : new FulfillmentPlan(FulfillmentMode.PHYSICAL_SHIPMENT, products);
        }

        public boolean requiresShippingQuote() { return mode == FulfillmentMode.PHYSICAL_SHIPMENT; }
    }

    public record ShippingDestination(String recipient, String phone, String addressLine,
                                      String locality, String postalCode, String countryCode) {
        public ShippingDestination {
            recipient = requireText(recipient, "recipient");
            phone = requireText(phone, "phone");
            addressLine = requireText(addressLine, "addressLine");
            locality = requireText(locality, "locality");
            postalCode = Objects.requireNonNull(postalCode, "postalCode").strip();
            countryCode = requireText(countryCode, "countryCode");
        }
    }

    public record ShippingQuoteCommand(Integer authenticatedUserId, FulfillmentPlan fulfillment,
                                       ShippingDestination destination, Instant observedAt) {
        public ShippingQuoteCommand {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            Objects.requireNonNull(fulfillment, "fulfillment");
            if (!fulfillment.requiresShippingQuote())
                throw new IllegalArgumentException("digital-only fulfillment does not require a shipping quote");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public record ShippingQuote(long feeVnd, String quoteReference, Instant quotedAt,
                                QuoteEvidencePersistence evidencePersistence) {
        public ShippingQuote {
            if (feeVnd < 0) throw new IllegalArgumentException("shipping fee must be nonnegative whole VND");
            quoteReference = requireText(quoteReference, "quoteReference");
            Objects.requireNonNull(quotedAt, "quotedAt");
            Objects.requireNonNull(evidencePersistence, "evidencePersistence");
        }

        public void requirePersistenceResolved() {
            if (evidencePersistence == QuoteEvidencePersistence.UNRESOLVED)
                throw new UnresolvedIntegrationContractException("shipping quote persistence owner is unresolved");
        }
    }

    private static List<AggregatedProductRequirement> canonicalAggregates(
            List<AggregatedProductRequirement> source) {
        Objects.requireNonNull(source, "products");
        if (source.isEmpty()) throw new IllegalArgumentException("Product batch must not be empty");
        var copy = new ArrayList<AggregatedProductRequirement>(source.size());
        for (AggregatedProductRequirement item : source)
            copy.add(Objects.requireNonNull(item, "Product aggregate"));
        copy.sort(Comparator.comparing(AggregatedProductRequirement::productId));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).productId().equals(copy.get(index).productId()))
                throw new IllegalArgumentException("duplicate aggregated Product " + copy.get(index).productId());
        }
        return List.copyOf(copy);
    }

    private static List<ProductRequirement> immutableRequirements(List<ProductRequirement> source) {
        Objects.requireNonNull(source, "requirements");
        var copy = new ArrayList<ProductRequirement>(source.size());
        for (ProductRequirement item : source)
            copy.add(Objects.requireNonNull(item, "Product requirement"));
        return List.copyOf(copy);
    }

    private static List<PersistedReservationReference> canonicalReservations(
            List<PersistedReservationReference> source) {
        Objects.requireNonNull(source, "reservations");
        if (source.isEmpty()) throw new IllegalArgumentException("reservation batch must not be empty");
        var copy = new ArrayList<PersistedReservationReference>(source.size());
        var reservationReferences = new HashSet<String>();
        for (PersistedReservationReference item : source) {
            PersistedReservationReference required = Objects.requireNonNull(item, "reservation");
            if (!reservationReferences.add(required.reservationReference()))
                throw new IllegalArgumentException("duplicate reservation " + required.reservationReference());
            copy.add(required);
        }
        copy.sort(Comparator.comparing(PersistedReservationReference::productId)
                .thenComparing(PersistedReservationReference::reservationReference));
        return List.copyOf(copy);
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return result;
    }

    private static String requireReference(String value, String name) {
        String result = requireText(value, name);
        if (result.length() > 128) throw new IllegalArgumentException(name + " must contain at most 128 characters");
        return result;
    }

    private static void requirePositive(Integer value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
