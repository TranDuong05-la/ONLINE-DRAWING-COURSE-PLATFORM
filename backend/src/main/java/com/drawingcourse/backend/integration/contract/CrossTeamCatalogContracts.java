package com.drawingcourse.backend.integration.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Database-free trusted catalog boundaries for future B/E adapters. These contracts are not Spring beans and
 * do not imply that a production adapter, catalog version, lock, or schema currently exists.
 */
public final class CrossTeamCatalogContracts {
    private CrossTeamCatalogContracts() { }

    @FunctionalInterface
    public interface TrustedCourseCatalog {
        Optional<CourseSnapshot> findCourse(Long courseId);
    }

    @FunctionalInterface
    public interface TrustedComboCatalog {
        Optional<ComboSnapshot> findCombo(Long comboId);
    }

    @FunctionalInterface
    public interface TrustedProductCatalog {
        Optional<ProductSnapshot> findProduct(Long productId);
    }

    public enum CapabilityStatus { SUPPORTED, UNSUPPORTED }

    public enum CourseConsistencyMode { VERSIONED, TRANSACTION_LOCKED, UNRESOLVED }

    public enum ProductConsistencyMode { VERSIONED, TRANSACTION_LOCKED, UNRESOLVED }

    /** Product requirement included with a Course; the Product catalog remains authoritative for Product facts. */
    public record IncludedProductKit(Long productId, int quantity) {
        public IncludedProductKit {
            requirePositive(productId, "productId");
            if (quantity <= 0) throw new IllegalArgumentException("kit quantity must be positive");
        }
    }

    /** Distinguishes a known empty kit list from a catalog that cannot yet provide kit information. */
    public record IncludedProductKits(CapabilityStatus status, List<IncludedProductKit> items) {
        public IncludedProductKits {
            Objects.requireNonNull(status, "status");
            items = immutableList(items, "items");
            if (status == CapabilityStatus.UNSUPPORTED && !items.isEmpty())
                throw new IllegalArgumentException("unsupported kit capability cannot contain items");
            var seen = new HashSet<Long>();
            for (IncludedProductKit item : items) {
                if (!seen.add(item.productId()))
                    throw new IllegalArgumentException("duplicate included Product " + item.productId());
            }
        }

        public static IncludedProductKits supported(List<IncludedProductKit> items) {
            return new IncludedProductKits(CapabilityStatus.SUPPORTED, items);
        }

        public static IncludedProductKits unsupported() {
            return new IncludedProductKits(CapabilityStatus.UNSUPPORTED, List.of());
        }

        public List<IncludedProductKit> requireSupported() {
            if (status != CapabilityStatus.SUPPORTED)
                throw new UnresolvedIntegrationContractException("Course included-Product contract is unsupported");
            return items;
        }
    }

    public record CourseConsistency(CourseConsistencyMode mode, OptionalLong version) {
        public CourseConsistency {
            Objects.requireNonNull(mode, "mode");
            version = Objects.requireNonNull(version, "version");
            if (version.isPresent() && version.getAsLong() < 0)
                throw new IllegalArgumentException("Course version must be nonnegative");
            if (mode == CourseConsistencyMode.VERSIONED && version.isEmpty())
                throw new IllegalArgumentException("VERSIONED Course consistency requires a version");
            if (mode != CourseConsistencyMode.VERSIONED && version.isPresent())
                throw new IllegalArgumentException("only VERSIONED Course consistency carries a version");
        }

        public static CourseConsistency versioned(long version) {
            return new CourseConsistency(CourseConsistencyMode.VERSIONED, OptionalLong.of(version));
        }

        /** The future adapter must prove a local lock/validation guarantee; read-only alone is insufficient. */
        public static CourseConsistency transactionLocked() {
            return new CourseConsistency(CourseConsistencyMode.TRANSACTION_LOCKED, OptionalLong.empty());
        }

        public static CourseConsistency unresolved() {
            return new CourseConsistency(CourseConsistencyMode.UNRESOLVED, OptionalLong.empty());
        }

        public void requireResolved() {
            if (mode == CourseConsistencyMode.UNRESOLVED)
                throw new UnresolvedIntegrationContractException("Course consistency contract is unresolved");
        }
    }

    public record CourseSnapshot(Long courseId, String title, long unitPriceVnd,
                                 boolean published, boolean purchasable, CourseConsistency consistency,
                                 IncludedProductKits includedProducts) {
        public CourseSnapshot {
            requirePositive(courseId, "courseId");
            title = requireText(title, "title");
            if (unitPriceVnd < 0) throw new IllegalArgumentException("Course price must be nonnegative whole VND");
            if (purchasable && !published)
                throw new IllegalArgumentException("an unpublished Course cannot be purchasable");
            Objects.requireNonNull(consistency, "consistency");
            Objects.requireNonNull(includedProducts, "includedProducts");
        }

        public void requireCheckoutReady() {
            if (!published || !purchasable)
                throw new UnresolvedIntegrationContractException("Course is not purchasable");
            consistency.requireResolved();
            includedProducts.requireSupported();
        }
    }

    public record ComboCourseComponent(int position, CourseSnapshot course) {
        public ComboCourseComponent {
            if (position < 0) throw new IllegalArgumentException("component position must be nonnegative");
            Objects.requireNonNull(course, "course");
        }
    }

    /**
     * definitionVersion is deliberately optional because Member B has not implemented one. A missing value is
     * representable for adaptation diagnostics but makes this snapshot unusable for Checkout.
     */
    public record ComboSnapshot(Long comboId, String title, long wholeComboPriceVnd,
                                OptionalLong definitionVersion,
                                List<ComboCourseComponent> orderedComponents,
                                boolean purchasable) {
        public ComboSnapshot {
            requirePositive(comboId, "comboId");
            title = requireText(title, "title");
            if (wholeComboPriceVnd < 0)
                throw new IllegalArgumentException("Combo price must be nonnegative whole VND");
            definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion");
            if (definitionVersion.isPresent() && definitionVersion.getAsLong() < 0)
                throw new IllegalArgumentException("Combo definition version must be nonnegative");
            orderedComponents = immutableList(orderedComponents, "orderedComponents");
            if (orderedComponents.isEmpty())
                throw new IllegalArgumentException("Combo components must not be empty");
            var seen = new HashSet<Long>();
            for (int index = 0; index < orderedComponents.size(); index++) {
                ComboCourseComponent component = orderedComponents.get(index);
                if (component.position() != index)
                    throw new IllegalArgumentException("Combo component positions must be contiguous and ordered");
                if (!seen.add(component.course().courseId()))
                    throw new IllegalArgumentException("duplicate Combo Course " + component.course().courseId());
                if (purchasable && !component.course().purchasable())
                    throw new IllegalArgumentException("purchasable Combo contains an unavailable Course");
            }
        }

        public void requireCheckoutReady() {
            if (!purchasable) throw new UnresolvedIntegrationContractException("Combo is not purchasable");
            if (definitionVersion.isEmpty())
                throw new UnresolvedIntegrationContractException("Member B Combo definition version is unavailable");
            orderedComponents.forEach(component -> component.course().requireCheckoutReady());
        }
    }

    public record ProductConsistency(ProductConsistencyMode mode, OptionalLong version) {
        public ProductConsistency {
            Objects.requireNonNull(mode, "mode");
            version = Objects.requireNonNull(version, "version");
            if (version.isPresent() && version.getAsLong() < 0)
                throw new IllegalArgumentException("Product version must be nonnegative");
            if (mode == ProductConsistencyMode.VERSIONED && version.isEmpty())
                throw new IllegalArgumentException("VERSIONED Product consistency requires a version");
            if (mode != ProductConsistencyMode.VERSIONED && version.isPresent())
                throw new IllegalArgumentException("only VERSIONED Product consistency carries a version");
        }

        public static ProductConsistency versioned(long version) {
            return new ProductConsistency(ProductConsistencyMode.VERSIONED, OptionalLong.of(version));
        }

        public static ProductConsistency transactionLocked() {
            return new ProductConsistency(ProductConsistencyMode.TRANSACTION_LOCKED, OptionalLong.empty());
        }

        public static ProductConsistency unresolved() {
            return new ProductConsistency(ProductConsistencyMode.UNRESOLVED, OptionalLong.empty());
        }

        public void requireResolved() {
            if (mode == ProductConsistencyMode.UNRESOLVED)
                throw new UnresolvedIntegrationContractException("Product consistency contract is unresolved");
        }
    }

    /**
     * Opaque immutable attributes avoid inventing Member E's weight/dimension schema. The future local shipping
     * adapter owns interpretation. UNSUPPORTED must fail Checkout rather than silently quote zero.
     */
    public record ShippingAttributes(CapabilityStatus status, Map<String, String> attributes) {
        public ShippingAttributes {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(attributes, "attributes");
            var copy = new LinkedHashMap<String, String>();
            attributes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String key = requireText(entry.getKey(), "shipping attribute key");
                String value = requireText(entry.getValue(), "shipping attribute value");
                copy.put(key, value);
            });
            attributes = Collections.unmodifiableMap(copy);
            if (status == CapabilityStatus.UNSUPPORTED && !attributes.isEmpty())
                throw new IllegalArgumentException("unsupported shipping capability cannot contain attributes");
        }

        public static ShippingAttributes supported(Map<String, String> attributes) {
            return new ShippingAttributes(CapabilityStatus.SUPPORTED, attributes);
        }

        public static ShippingAttributes unsupported() {
            return new ShippingAttributes(CapabilityStatus.UNSUPPORTED, Map.of());
        }

        public void requireSupported() {
            if (status != CapabilityStatus.SUPPORTED)
                throw new UnresolvedIntegrationContractException("Member E shipping attributes are unsupported");
        }
    }

    public record ProductSnapshot(Long productId, String title, long unitPriceVnd,
                                  ProductConsistency consistency, boolean purchasable,
                                  long availableQuantity, ShippingAttributes shippingAttributes) {
        public ProductSnapshot {
            requirePositive(productId, "productId");
            title = requireText(title, "title");
            if (unitPriceVnd < 0) throw new IllegalArgumentException("Product price must be nonnegative whole VND");
            Objects.requireNonNull(consistency, "consistency");
            if (availableQuantity < 0) throw new IllegalArgumentException("available quantity must be nonnegative");
            Objects.requireNonNull(shippingAttributes, "shippingAttributes");
        }

        public void requireCheckoutReady(long requestedQuantity) {
            if (requestedQuantity <= 0) throw new IllegalArgumentException("requested quantity must be positive");
            if (!purchasable) throw new UnresolvedIntegrationContractException("Product is not purchasable");
            consistency.requireResolved();
            shippingAttributes.requireSupported();
            if (availableQuantity < requestedQuantity)
                throw new UnresolvedIntegrationContractException("authoritative Product stock is insufficient");
        }
    }

    public static final class UnresolvedIntegrationContractException extends IllegalStateException {
        public UnresolvedIntegrationContractException(String message) { super(message); }
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return result;
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static <T> List<T> immutableList(List<T> source, String name) {
        Objects.requireNonNull(source, name);
        var copy = new ArrayList<T>(source.size());
        for (T item : source) copy.add(Objects.requireNonNull(item, name + " item"));
        return List.copyOf(copy);
    }
}
