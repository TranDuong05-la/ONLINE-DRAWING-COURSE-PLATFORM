package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.drawingcourse.backend.dto.AdminVoucherCreateRequest;
import com.drawingcourse.backend.dto.AdminVoucherResponse;
import com.drawingcourse.backend.dto.AdminVoucherStatusRequest;
import com.drawingcourse.backend.dto.AdminVoucherUpdateRequest;
import com.drawingcourse.backend.dto.PageResponse;
import com.drawingcourse.backend.service.AdminVoucherService;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/vouchers")
@ConditionalOnProperty(name = "app.voucher.admin-api-enabled", havingValue = "true")
public class AdminVoucherController {
    private final AdminVoucherService vouchers;

    public AdminVoucherController(AdminVoucherService vouchers) {
        this.vouchers = Objects.requireNonNull(vouchers);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<AdminVoucherResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success("Admin vouchers retrieved successfully",
                vouchers.list(page, size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AdminVoucherResponse>> get(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.success("Admin voucher retrieved successfully", vouchers.get(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AdminVoucherResponse>> create(
            @Valid @RequestBody AdminVoucherCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Voucher created successfully", vouchers.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AdminVoucherResponse>> update(
            @PathVariable Integer id, @Valid @RequestBody AdminVoucherUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Voucher financial terms updated successfully",
                vouchers.update(id, request)));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AdminVoucherResponse>> updateStatus(
            @PathVariable Integer id, @Valid @RequestBody AdminVoucherStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Voucher status updated successfully",
                vouchers.updateStatus(id, request)));
    }
}
