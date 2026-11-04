package com.artacademy.product;

import com.artacademy.supply.catalog.SupplyProductRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cầu nối B ↔ E: danh mục sản phẩm làm quà tặng kèm đọc thẳng từ bảng {@code products} của E
 * (qua {@link SupplyProductRepository}). Thay cho bản tạm {@link InMemoryProductCatalog} (trống mỗi lần khởi động lại).
 *
 * <p>Chỉ đọc dữ liệu cục bộ, không giữ/trừ tồn kho. Bật bằng {@code app.products.stand-in=false} (mặc định của ứng dụng).
 * B lưu {@code product_id} kiểu INT còn E dùng Long: id vượt phạm vi INT được coi là không tồn tại.
 */
@Component
@ConditionalOnProperty(name = "app.products.stand-in", havingValue = "false")
public class SupplyProductCatalog implements ProductCatalog {

    private final SupplyProductRepository products;

    public SupplyProductCatalog(SupplyProductRepository products) {
        this.products = products;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Integer, KitProduct> findByIds(Collection<Integer> ids) {
        Map<Integer, KitProduct> result = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        List<Long> wanted = ids.stream().filter(id -> id != null && id > 0).map(Integer::longValue).distinct().toList();
        for (Product p : products.findAllById(wanted)) {
            if (p.getId() == null || p.getId() > Integer.MAX_VALUE) {
                continue;
            }
            int id = p.getId().intValue();
            result.put(id, new KitProduct(id, p.getSku(), p.getName(), p.getThumbnailUrl(), p.isActive()));
        }
        return result;
    }
}
