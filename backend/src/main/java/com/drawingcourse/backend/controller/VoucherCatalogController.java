package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.drawingcourse.backend.dto.PageResponse;
import com.drawingcourse.backend.dto.VoucherCatalogItem;
import com.drawingcourse.backend.service.VoucherCatalogQueryService;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vouchers")
@ConditionalOnProperty(name = "app.voucher.api-enabled", havingValue = "true")
public final class VoucherCatalogController {
    private final VoucherCatalogQueryService vouchers;

    public VoucherCatalogController(VoucherCatalogQueryService vouchers) {
        this.vouchers = Objects.requireNonNull(vouchers);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<VoucherCatalogItem>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                "Vouchers retrieved successfully", vouchers.list(page, size)));
    }
}
