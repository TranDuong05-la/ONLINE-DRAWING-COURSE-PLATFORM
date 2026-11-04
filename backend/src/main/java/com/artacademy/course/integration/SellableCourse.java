package com.artacademy.course.integration;

import java.util.List;

/**
 * Thông tin khóa học để D (giỏ hàng/đơn hàng) dùng khi thêm vào giỏ và checkout.
 * effectivePrice = giá giảm nếu có, không thì giá gốc. D PHẢI tự lưu lại giá lúc mua vào đơn hàng.
 */
public record SellableCourse(
        Long courseId,
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

    /** Compatibility constructor for existing callers that have no unavailable-kit evidence. */
    public SellableCourse(Long courseId, String title, String thumbnailUrl, long price, Long discountPrice,
                          long effectivePrice, List<SellableKit> kits) {
        this(courseId, title, thumbnailUrl, price, discountPrice, effectivePrice, kits, List.of());
    }

    public CourseSellableStatus status() {
        return unavailableKits.isEmpty() ? CourseSellableStatus.AVAILABLE : CourseSellableStatus.KIT_UNAVAILABLE;
    }

    public boolean isSellable() { return status() == CourseSellableStatus.AVAILABLE; }

    /** Quà tặng kèm (chỉ gồm sản phẩm còn bán) để D/E trừ kho và giao hàng. */
    public record SellableKit(Long productId, String sku, String name, int quantity) {
    }

    public record UnavailableKit(Long productId, int quantity, String reason) {
        public static final String INACTIVE = "INACTIVE";
        public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    }
}
