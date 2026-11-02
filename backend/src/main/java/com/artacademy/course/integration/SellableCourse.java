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
        List<SellableKit> kits) {

    /** Quà tặng kèm (chỉ gồm sản phẩm còn bán) để D/E trừ kho và giao hàng. */
    public record SellableKit(Long productId, String sku, String name, int quantity) {
    }
}
