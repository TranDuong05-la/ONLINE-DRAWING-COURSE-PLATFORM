package com.artacademy.product;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * BẢN TẠM thay cho danh mục sản phẩm của E: dữ liệu nằm trong bộ nhớ, bắt đầu rỗng (dữ liệu mẫu chỉ được nạp khi
 * bật app.seed.enabled). Dùng để chạy thử và test phần quà tặng kèm khi E chưa tích hợp.
 * Khi E có bean ProductCatalog thật: đặt app.products.stand-in=false để tắt class này và controller đi kèm.
 */
@Component
@ConditionalOnProperty(name = "app.products.stand-in", havingValue = "true", matchIfMissing = true)
public class InMemoryProductCatalog implements ProductCatalog {

    /** Sản phẩm tạm, có thêm giá để màn chọn quà của admin hiển thị. */
    public record StandInProduct(KitProduct product, long price) {
    }

    private final Map<Integer, StandInProduct> products = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();

    public StandInProduct add(String sku, String name, long price, boolean active) {
        int id = ids.incrementAndGet();
        StandInProduct p = new StandInProduct(new KitProduct(id, sku, name, null, active), price);
        products.put(id, p);
        return p;
    }

    public void setActive(Integer id, boolean active) {
        products.computeIfPresent(id, (k, p) -> new StandInProduct(
                new KitProduct(p.product().id(), p.product().sku(), p.product().name(),
                        p.product().thumbnailUrl(), active), p.price()));
    }

    public void clear() {
        products.clear();
    }

    public boolean isEmpty() {
        return products.isEmpty();
    }

    public Optional<KitProduct> findBySku(String sku) {
        return products.values().stream().map(StandInProduct::product).filter(p -> p.sku().equals(sku)).findFirst();
    }

    /** Sản phẩm đang bán, xếp theo tên (cho màn chọn quà). */
    public List<StandInProduct> listActive() {
        return products.values().stream().filter(p -> p.product().active())
                .sorted((a, b) -> a.product().name().compareToIgnoreCase(b.product().name())).toList();
    }

    @Override
    public Map<Integer, KitProduct> findByIds(Collection<Integer> wanted) {
        Map<Integer, KitProduct> result = new LinkedHashMap<>();
        for (Integer id : wanted) {
            StandInProduct p = products.get(id);
            if (p != null) {
                result.put(id, p.product());
            }
        }
        return result;
    }
}
