-- Combo (gói nhiều khóa học) là MỘT dòng trong giỏ hàng và trong đơn hàng: thêm loại 'COMBO' vào ràng buộc item_type.
-- Chỉ nới ràng buộc CHECK (không đổi cột, không đổi dữ liệu cũ); khóa học và sản phẩm giữ nguyên quy tắc cũ.
-- Combo luôn có số lượng 1, giống khóa học. Yêu cầu MySQL 8.0.16+ (CHECK có hiệu lực).
-- Không sửa V2/V3 đã áp dụng (Flyway kiểm tra checksum).

ALTER TABLE cart_items DROP CHECK ck_cart_items_values;
ALTER TABLE cart_items ADD CONSTRAINT ck_cart_items_values CHECK (
    quantity > 0 AND item_type IN ('COURSE', 'PRODUCT', 'COMBO') AND (item_type = 'PRODUCT' OR quantity = 1)
);

ALTER TABLE order_items DROP CHECK ck_order_items_values;
ALTER TABLE order_items ADD CONSTRAINT ck_order_items_values CHECK (
    quantity > 0 AND item_type IN ('COURSE', 'PRODUCT', 'COMBO') AND (item_type = 'PRODUCT' OR quantity = 1)
);
