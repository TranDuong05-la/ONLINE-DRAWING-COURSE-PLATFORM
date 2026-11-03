package com.artacademy.product;

import com.artacademy.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Danh sách sản phẩm để admin chọn quà tặng kèm. Chỉ ADMIN (SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/products")
@Tag(name = "Admin - Courses", description = "Quản trị khóa học, module, quà tặng kèm (chỉ ADMIN)")
public class AdminProductController {

    public record ProductResponse(Long id, String sku, String name, long price, String thumbnailUrl) {
    }

    private final ProductRepository productRepository;

    public AdminProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** Chỉ trả sản phẩm đang bán (active), vì chỉ những sản phẩm này mới được chọn làm quà. */
    @GetMapping
    @Operation(summary = "Sản phẩm đang bán để chọn làm quà tặng kèm")
    public ApiResponse<List<ProductResponse>> list() {
        List<ProductResponse> items = productRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(p -> new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getPrice(), p.getThumbnailUrl()))
                .toList();
        return ApiResponse.success("OK", items);
    }
}
