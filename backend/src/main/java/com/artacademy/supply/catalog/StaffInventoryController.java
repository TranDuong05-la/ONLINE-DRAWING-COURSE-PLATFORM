package com.artacademy.supply.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.artacademy.dto.ApiResponse;
import com.artacademy.supply.catalog.CatalogDtos.InventoryTransactionResponse;
import com.artacademy.supply.catalog.CatalogDtos.ProductResponse;
import com.artacademy.supply.catalog.CatalogDtos.StockImportRequest;
import com.artacademy.supply.common.PageResult;
import com.artacademy.supply.common.SupplyCurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Xem tồn, nhập kho, lịch sử kho. STAFF hoặc ADMIN (SecurityConfig: /api/staff/**). */
@RestController
@RequestMapping("/api/staff/supplies")
@Tag(name = "Staff - Inventory", description = "Tồn kho và nhập kho học cụ (E)")
public class StaffInventoryController {

    private final CatalogService catalogService;
    private final SupplyCurrentUser currentUser;

    public StaffInventoryController(CatalogService catalogService, SupplyCurrentUser currentUser) {
        this.catalogService = catalogService;
        this.currentUser = currentUser;
    }

    @GetMapping("/products")
    @Operation(summary = "Tất cả học cụ kèm số tồn (lọc đang bán/ẩn, sắp hết)")
    public ApiResponse<PageResult<ProductResponse>> products(@RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean lowStock, @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("OK",
                catalogService.searchManager(q, categoryId, active, lowStock, sort, page, size));
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Chi tiết tồn kho")
    public ApiResponse<ProductResponse> product(@PathVariable Long id) {
        return ApiResponse.success("OK", catalogService.managerDetail(id));
    }

    @PostMapping("/products/{id}/stock-imports")
    @Operation(summary = "Nhập kho")
    public ApiResponse<ProductResponse> importStock(@PathVariable Long id, @Valid @RequestBody StockImportRequest req) {
        return ApiResponse.success("Đã nhập " + req.quantity() + " vào kho",
                catalogService.importStock(id, req.quantity(), req.note(), currentUser.require().id()));
    }

    @GetMapping("/products/{id}/inventory-transactions")
    @Operation(summary = "Lịch sử nhập/giữ/xuất kho")
    public ApiResponse<PageResult<InventoryTransactionResponse>> transactions(@PathVariable Long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return ApiResponse.success("OK", catalogService.transactions(id, page, size));
    }
}
