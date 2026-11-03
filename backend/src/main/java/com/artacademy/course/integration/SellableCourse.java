package com.artacademy.course.integration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thông tin khóa học để D (giỏ hàng/đơn hàng) dùng khi thêm vào giỏ và checkout.
 * effectivePrice = giá giảm nếu có, không thì giá gốc. D PHẢI tự lưu lại giá lúc mua vào đơn hàng.
 *
 * kits: quà tặng kèm CÒN BÁN (sản phẩm vật lý, giá bán trong đơn bằng 0).
 * unavailableKits: quà đã cấu hình nhưng sản phẩm đã ngừng bán hoặc không còn trong danh mục của E.
 * Mọi quà đều bắt buộc (nhóm đã chốt): còn quà không bán được thì status() = KIT_UNAVAILABLE và isSellable() = false.
 * B không tự bỏ quà để tiếp tục bán. Tồn kho (hết hàng tạm thời) do E quản lý, D kiểm tra lúc checkout.
 */
public record SellableCourse(
        Integer courseId,
        String title,
        String thumbnailUrl,
        long price,
        Long discountPrice,
        long effectivePrice,
        List<SellableKit> kits,
        List<UnavailableKit> unavailableKits) {

    public SellableCourse {
        kits = List.copyOf(kits);
        unavailableKits = List.copyOf(unavailableKits);
    }

    /** Mọi quà đã cấu hình đều bắt buộc: thiếu một món thì khóa vẫn hiển thị nhưng không bán được (KIT_UNAVAILABLE). */
    public CourseSellableStatus status() {
        return unavailableKits.isEmpty() ? CourseSellableStatus.AVAILABLE : CourseSellableStatus.KIT_UNAVAILABLE;
    }

    public boolean isSellable() {
        return status() == CourseSellableStatus.AVAILABLE;
    }

    /** Quà tặng kèm còn bán, để D/E trừ kho và giao hàng. */
    public record SellableKit(Integer productId, String sku, String name, int quantity) {

        /**
         * Cộng gộp số lượng theo sản phẩm (cùng productId thì cộng quantity), giữ thứ tự xuất hiện đầu tiên.
         * Dùng khi một đơn có nhiều khóa/combo tặng cùng một sản phẩm.
         */
        public static List<SellableKit> mergeByProduct(Collection<SellableKit> kits) {
            Map<Integer, SellableKit> merged = new LinkedHashMap<>();
            for (SellableKit kit : kits) {
                merged.merge(kit.productId(), kit,
                        (a, b) -> new SellableKit(a.productId(), a.sku(), a.name(),
                                Math.addExact(a.quantity(), b.quantity())));
            }
            return List.copyOf(merged.values());
        }
    }

    /** Quà đã cấu hình nhưng hiện không bán được. reason: NOT_FOUND (E không còn sản phẩm này) hoặc INACTIVE (đã ngừng bán). */
    public record UnavailableKit(Integer productId, int quantity, String reason) {
        public static final String NOT_FOUND = "NOT_FOUND";
        public static final String INACTIVE = "INACTIVE";
    }
}
