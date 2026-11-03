package com.artacademy.supply.catalog;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.artacademy.product.ProductCategory;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {
    List<ProductCategory> findByActiveTrueOrderBySortOrderAscNameAsc();

    List<ProductCategory> findAllByOrderBySortOrderAscNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
