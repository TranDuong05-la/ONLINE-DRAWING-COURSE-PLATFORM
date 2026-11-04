package com.artacademy.product;

import com.artacademy.dto.ApiResponse;
import com.artacademy.supply.catalog.SupplyProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Danh sách sản phẩm đang bán để admin chọn làm quà tặng kèm, lấy từ bảng {@code products} của E.
 * Cùng đường dẫn và dạng dữ liệu với bản tạm {@link StandInProductController} (chỉ bật khi tắt bản tạm). Chỉ ADMIN (SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/products")
@ConditionalOnProperty(name = "app.products.stand-in", havingValue = "false")
@Tag(name = "Admin - Courses", description = "Quản trị khóa học, module, quà tặng kèm (chỉ ADMIN)")
public class SupplyKitProductController {

    public record ProductResponse(Integer id, String sku, String name, long price, String thumbnailUrl) {
    }

    private final SupplyProductRepository products;

    public SupplyKitProductController(SupplyProductRepository products) {
        this.products = products;
    }

    /** Chỉ trả sản phẩm đang bán (active), vì chỉ những sản phẩm này mới được chọn làm quà. */
    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Sản phẩm đang bán để chọn làm quà tặng kèm (đọc từ kho của E)")
    public ApiResponse<List<ProductResponse>> list() {
        List<ProductResponse> items = products.findAll().stream()
                .filter(p -> p.isActive() && p.getId() != null && p.getId() <= Integer.MAX_VALUE)
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .map(p -> new ProductResponse(p.getId().intValue(), p.getSku(), p.getName(), p.getPrice(),
                        p.getThumbnailUrl()))
                .toList();
        return ApiResponse.success("OK", items);
    }
}
