package com.artacademy.course;

import com.artacademy.course.dto.KitDto;
import com.artacademy.product.ProductCatalog;
import com.artacademy.product.ProductCatalog.KitProduct;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Ghép quà tặng kèm (lưu ở B) với thông tin sản phẩm (thuộc E, tra qua {@link ProductCatalog}).
 * Sản phẩm không còn tồn tại hoặc đã ngừng bán thì không được coi là quà "còn bán".
 */
@Component
public class CourseKitLookup {

    /** Một món quà đã ghép với sản phẩm. product = null nghĩa là sản phẩm không còn tồn tại trong danh mục. */
    public record ResolvedKit(CourseIncludedKit kit, KitProduct product) {

        public boolean isActive() {
            return product != null && product.active();
        }
    }

    private final CourseKitRepository kitRepository;
    private final ProductCatalog productCatalog;

    public CourseKitLookup(CourseKitRepository kitRepository, ProductCatalog productCatalog) {
        this.kitRepository = kitRepository;
        this.productCatalog = productCatalog;
    }

    /** Tất cả quà của khóa (kể cả quà đã ngừng bán hoặc không còn tồn tại), theo thứ tự thêm vào. */
    public List<ResolvedKit> resolveAll(Integer courseId) {
        return resolve(kitRepository.findByCourse(courseId));
    }

    /** Chỉ quà còn bán. */
    public List<ResolvedKit> resolveActive(Integer courseId) {
        return resolveAll(courseId).stream().filter(ResolvedKit::isActive).toList();
    }

    /** Cờ về quà của một khóa: hasActive (nhãn "Tặng kèm dụng cụ"), hasUnavailable (nhãn "Tạm thiếu quà"). */
    public record KitFlags(boolean hasActive, boolean hasUnavailable) {
        public static final KitFlags NONE = new KitFlags(false, false);
    }

    /** Cờ quà của nhiều khóa trong 1 lượt truy vấn. Khóa không có quà thì không có trong kết quả. */
    public Map<Integer, KitFlags> flagsFor(java.util.Collection<Integer> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }
        Set<Integer> active = new HashSet<>();
        Set<Integer> unavailable = new HashSet<>();
        for (ResolvedKit rk : resolve(kitRepository.findByCourses(courseIds))) {
            (rk.isActive() ? active : unavailable).add(rk.kit().getCourse().getId());
        }
        Map<Integer, KitFlags> result = new java.util.HashMap<>();
        for (Integer id : courseIds) {
            if (active.contains(id) || unavailable.contains(id)) {
                result.put(id, new KitFlags(active.contains(id), unavailable.contains(id)));
            }
        }
        return result;
    }

    /** Quà của khóa ở dạng DTO cho học viên (chỉ quà còn bán). */
    public List<KitDto> publicKits(Integer courseId) {
        return resolveActive(courseId).stream().map(CourseKitLookup::toDto).toList();
    }

    /** Quà của khóa ở dạng DTO cho admin (hiện cả quà đã ngừng bán để admin biết mà gỡ). */
    public List<KitDto> adminKits(Integer courseId) {
        return resolveAll(courseId).stream().map(CourseKitLookup::toDto).toList();
    }

    public static KitDto toDto(ResolvedKit rk) {
        KitProduct p = rk.product();
        if (p == null) {
            return new KitDto(rk.kit().getProductId(), null, "Sản phẩm #" + rk.kit().getProductId()
                    + " (không còn trong danh mục)", null, rk.kit().getQuantity(), false);
        }
        return new KitDto(p.id(), p.sku(), p.name(), p.thumbnailUrl(), rk.kit().getQuantity(), p.active());
    }

    private List<ResolvedKit> resolve(List<CourseIncludedKit> kits) {
        if (kits.isEmpty()) {
            return List.of();
        }
        Set<Integer> productIds = kits.stream().map(CourseIncludedKit::getProductId).collect(Collectors.toSet());
        Map<Integer, KitProduct> products = productCatalog.findByIds(productIds);
        return kits.stream().map(k -> new ResolvedKit(k, products.get(k.getProductId()))).toList();
    }
}
