package com.artacademy.bundle;

import com.artacademy.bundle.dto.BundleDto;
import com.artacademy.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API combo cho học viên. Công khai (không cần đăng nhập). */
@RestController
@RequestMapping("/api/bundles")
@Tag(name = "Bundles", description = "Combo khóa học cho học viên (công khai)")
public class BundleController {

    private final BundlePublicService bundleService;

    public BundleController(BundlePublicService bundleService) {
        this.bundleService = bundleService;
    }

    @GetMapping
    @Operation(summary = "Danh sách combo đang bán",
            description = "Combo đang bật và mọi khóa trong combo đều đang PUBLISHED.")
    public ApiResponse<List<BundleDto>> list() {
        return ApiResponse.success("OK", bundleService.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết combo")
    public ApiResponse<BundleDto> detail(@PathVariable Long id) {
        return ApiResponse.success("OK", bundleService.get(id));
    }
}
