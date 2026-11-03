package com.artacademy.supply.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.artacademy.dto.ApiResponse;
import com.artacademy.supply.catalog.CatalogDtos.CategoryResponse;
import com.artacademy.supply.catalog.CatalogDtos.ProductResponse;
import com.artacademy.supply.common.PageResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Cửa hàng học cụ cho học viên. Cần đăng nhập như các API khác. */
@RestController
@RequestMapping("/api/supplies")
@Tag(name = "Supplies - Shop", description = "Danh sách và chi tiết học cụ (E)")
public class SupplyCatalogController {

    private final CatalogService catalogService;

    public SupplyCatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/categories")
    @Operation(summary = "Danh mục học cụ đang hiện")
    public ApiResponse<List<CategoryResponse>> categories() {
        return ApiResponse.success("OK", catalogService.activeCategories());
    }

    @GetMapping("/products")
    @Operation(summary = "Tìm/lọc học cụ đang bán", description = "sort: newest | price_asc | price_desc | rating | name")
    public ApiResponse<PageResult<ProductResponse>> products(@RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Boolean inStock,
            @RequestParam(required = false) Long minPrice, @RequestParam(required = false) Long maxPrice,
            @RequestParam(defaultValue = "newest") String sort, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("OK",
                catalogService.searchShop(q, categoryId, inStock, minPrice, maxPrice, sort, page, size));
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Chi tiết học cụ")
    public ApiResponse<ProductResponse> product(@PathVariable Long id) {
        return ApiResponse.success("OK", catalogService.shopDetail(id));
    }
}
