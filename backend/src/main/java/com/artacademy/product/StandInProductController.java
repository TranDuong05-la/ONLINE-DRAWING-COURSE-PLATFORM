package com.artacademy.product;

import com.artacademy.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BẢN TẠM: danh sách sản phẩm để admin chọn quà tặng kèm, lấy từ {@link InMemoryProductCatalog}.
 * Khi E có API quản lý sản phẩm thật (cùng đường dẫn /api/admin/products) thì đặt app.products.stand-in=false
 * để tắt controller này. Chỉ ADMIN (SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/products")
@ConditionalOnProperty(name = "app.products.stand-in", havingValue = "true", matchIfMissing = true)
@Tag(name = "Admin - Courses", description = "Quản trị khóa học, module, quà tặng kèm (chỉ ADMIN)")
public class StandInProductController {

    public record ProductResponse(Integer id, String sku, String name, long price, String thumbnailUrl) {
    }

    private final InMemoryProductCatalog catalog;

    public StandInProductController(InMemoryProductCatalog catalog) {
        this.catalog = catalog;
    }

    /** Chỉ trả sản phẩm đang bán (active), vì chỉ những sản phẩm này mới được chọn làm quà. */
    @GetMapping
    @Operation(summary = "Sản phẩm đang bán để chọn làm quà tặng kèm (bản tạm, E sẽ thay)")
    public ApiResponse<List<ProductResponse>> list() {
        List<ProductResponse> items = catalog.listActive().stream()
                .map(p -> new ProductResponse(p.product().id(), p.product().sku(), p.product().name(), p.price(),
                        p.product().thumbnailUrl()))
                .toList();
        return ApiResponse.success("OK", items);
    }
}
