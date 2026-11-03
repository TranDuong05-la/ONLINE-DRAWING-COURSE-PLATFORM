package com.artacademy.product;

import java.util.Collection;
import java.util.Map;

/**
 * HỢP ĐỒNG B cần từ E: tra cứu sản phẩm để cấu hình và hiển thị quà tặng kèm của khóa học.
 * E (chủ sở hữu bảng products và API quản lý sản phẩm) viết một bean implements interface này.
 * B chỉ lưu product_id (INT) trong course_included_kits, không ánh xạ bảng products và không có khóa ngoại.
 *
 * Khi E tích hợp: đặt app.products.stand-in=false để tắt bản tạm {@link InMemoryProductCatalog}.
 */
public interface ProductCatalog {

    /**
     * Tìm nhiều sản phẩm cùng lúc. Id không có trong kết quả nghĩa là sản phẩm không tồn tại.
     * Chỉ là đọc dữ liệu cục bộ (không gọi HTTP), không giữ/trừ tồn kho.
     */
    Map<Integer, KitProduct> findByIds(Collection<Integer> ids);

    /** Thông tin tối thiểu của sản phẩm dùng làm quà. active=false: sản phẩm đã ngừng bán, không được tặng kèm. */
    record KitProduct(Integer id, String sku, String name, String thumbnailUrl, boolean active) {
    }
}
