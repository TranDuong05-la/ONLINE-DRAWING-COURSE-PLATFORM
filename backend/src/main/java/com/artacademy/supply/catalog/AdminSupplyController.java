package com.artacademy.supply.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.artacademy.dto.ApiResponse;
import com.artacademy.supply.catalog.CatalogDtos.CategoryRequest;
import com.artacademy.supply.catalog.CatalogDtos.CategoryResponse;
import com.artacademy.supply.catalog.CatalogDtos.ProductRequest;
import com.artacademy.supply.catalog.CatalogDtos.ProductResponse;
import com.artacademy.supply.catalog.CatalogDtos.ProductStatusRequest;
import com.artacademy.supply.common.SupplyCurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Tạo/sửa/ẩn học cụ và danh mục. Chỉ ADMIN (SecurityConfig: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/supplies")
@Tag(name = "Admin - Supplies", description = "Quản trị học cụ và danh mục (E)")
public class AdminSupplyController {

    private final CatalogService catalogService;
    private final SupplyCurrentUser currentUser;

    public AdminSupplyController(CatalogService catalogService, SupplyCurrentUser currentUser) {
        this.catalogService = catalogService;
        this.currentUser = currentUser;
    }

    @GetMapping("/categories")
    @Operation(summary = "Tất cả danh mục (kể cả đang ẩn)")
    public ApiResponse<List<CategoryResponse>> categories() {
        return ApiResponse.success("OK", catalogService.allCategories());
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo danh mục")
    public ApiResponse<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest req) {
        return ApiResponse.success("Đã tạo danh mục", catalogService.createCategory(req));
    }

    @PutMapping("/categories/{id}")
    @Operation(summary = "Sửa danh mục")
    public ApiResponse<CategoryResponse> updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        return ApiResponse.success("Đã cập nhật danh mục", catalogService.updateCategory(id, req));
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo học cụ (initialStock ghi thành lần nhập kho đầu)")
    public ApiResponse<ProductResponse> create(@Valid @RequestBody ProductRequest req) {
        return ApiResponse.success("Đã tạo học cụ", catalogService.createProduct(req, currentUser.require().id()));
    }

    @PutMapping("/products/{id}")
    @Operation(summary = "Sửa học cụ (không sửa tồn kho ở đây)")
    public ApiResponse<ProductResponse> update(@PathVariable Long id, @Valid @RequestBody ProductRequest req) {
        return ApiResponse.success("Đã cập nhật học cụ", catalogService.updateProduct(id, req));
    }

    @PatchMapping("/products/{id}/status")
    @Operation(summary = "Ẩn/hiện học cụ")
    public ApiResponse<ProductResponse> changeStatus(@PathVariable Long id,
            @Valid @RequestBody ProductStatusRequest req) {
        return ApiResponse.success(req.active() ? "Đã hiện học cụ" : "Đã ẩn học cụ",
                catalogService.changeActive(id, req.active()));
    }
}
