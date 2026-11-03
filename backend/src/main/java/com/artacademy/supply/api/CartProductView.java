package com.artacademy.supply.api;

/**
 * purchasable = false khi sản phẩm bị ẩn. available = tồn thực tế - đang giữ, đọc tại lúc gọi;
 * chỉ để hiển thị, việc giữ hàng thật sự xảy ra ở reserveForOrder.
 */
public record CartProductView(Long id, String sku, String name, long priceVnd, String thumbnailUrl,
        int available, boolean purchasable) {
}
