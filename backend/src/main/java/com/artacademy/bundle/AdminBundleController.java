package com.artacademy.bundle;

import com.artacademy.bundle.dto.AdminBundleDto;
import com.artacademy.bundle.dto.BundleActiveRequest;
import com.artacademy.bundle.dto.BundleRequest;
import com.artacademy.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

/** API quản trị combo. Chỉ ADMIN (SecurityConfig). Không xóa, chỉ bật/tắt. */
@RestController
@RequestMapping("/api/admin/bundles")
@Tag(name = "Admin - Bundles", description = "Quản trị combo khóa học (chỉ ADMIN)")
public class AdminBundleController {

    private final BundleAdminService bundleService;

    public AdminBundleController(BundleAdminService bundleService) {
        this.bundleService = bundleService;
    }

    @GetMapping
    @Operation(summary = "Danh sách combo (kể cả đang tắt)")
    public ApiResponse<List<AdminBundleDto>> list() {
        return ApiResponse.success("OK", bundleService.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo combo",
            description = "Tối thiểu 2 khóa PUBLISHED, giá combo nhỏ hơn tổng giá thực bán các khóa.")
    public ApiResponse<AdminBundleDto> create(@Valid @RequestBody BundleRequest req) {
        return ApiResponse.success("Đã tạo combo", bundleService.create(req));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết combo cho admin")
    public ApiResponse<AdminBundleDto> get(@PathVariable Long id) {
        return ApiResponse.success("OK", bundleService.get(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Sửa combo")
    public ApiResponse<AdminBundleDto> update(@PathVariable Long id, @Valid @RequestBody BundleRequest req) {
        return ApiResponse.success("Đã lưu combo", bundleService.update(id, req));
    }

    @PatchMapping("/{id}/active")
    @Operation(summary = "Bật/tắt combo")
    public ApiResponse<AdminBundleDto> setActive(@PathVariable Long id,
                                                 @Valid @RequestBody BundleActiveRequest req) {
        return ApiResponse.success("Đã cập nhật combo", bundleService.setActive(id, req.active()));
    }
}
