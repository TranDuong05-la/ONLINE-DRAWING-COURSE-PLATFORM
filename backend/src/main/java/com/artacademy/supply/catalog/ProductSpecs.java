package com.artacademy.supply.catalog;

import org.springframework.data.jpa.domain.Specification;

import com.artacademy.product.Product;

import jakarta.persistence.criteria.JoinType;

final class ProductSpecs {
    private ProductSpecs() {
    }

    /** Nạp kèm danh mục (left join vì sản phẩm B tạo có thể chưa có danh mục). */
    static Specification<Product> fetchCategory() {
        return (root, query, cb) -> {
            if (query != null && Long.class != query.getResultType() && long.class != query.getResultType()) {
                root.fetch("category", JoinType.LEFT);
            }
            return cb.conjunction();
        };
    }

    static Specification<Product> keyword(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String like = "%" + q.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(cb.like(cb.lower(root.get("name")), like),
                cb.like(cb.lower(root.get("sku")), like));
    }

    static Specification<Product> category(Long categoryId) {
        return categoryId == null ? null
                : (root, query, cb) -> cb.equal(root.join("category", JoinType.LEFT).get("id"), categoryId);
    }

    static Specification<Product> active(Boolean active) {
        return active == null ? null : (root, query, cb) -> cb.equal(root.get("active"), active);
    }

    /** Không có danh mục hoặc danh mục đang hiện. */
    static Specification<Product> categoryVisible() {
        return (root, query, cb) -> {
            var c = root.join("category", JoinType.LEFT);
            return cb.or(cb.isNull(c.get("id")), cb.isTrue(c.get("active")));
        };
    }

    static Specification<Product> inStock(Boolean inStock) {
        if (inStock == null || !inStock) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThan(
                cb.diff(root.<Integer>get("stockOnHand"), root.<Integer>get("stockReserved")), 0);
    }

    static Specification<Product> lowStock(Boolean lowStock) {
        if (lowStock == null || !lowStock) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(
                cb.diff(root.<Integer>get("stockOnHand"), root.<Integer>get("stockReserved")),
                root.<Integer>get("lowStockThreshold"));
    }

    static Specification<Product> priceBetween(Long min, Long max) {
        if (min == null && max == null) {
            return null;
        }
        return (root, query, cb) -> {
            if (min != null && max != null) {
                return cb.between(root.get("price"), min, max);
            }
            return min != null ? cb.greaterThanOrEqualTo(root.get("price"), min)
                    : cb.lessThanOrEqualTo(root.get("price"), max);
        };
    }
}
