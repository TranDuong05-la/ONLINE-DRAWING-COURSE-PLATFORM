-- Phần E: học cụ, tồn kho, giữ hàng, giao hàng, đánh giá.
-- Viết theo mapping JPA hiện tại của com.artacademy.product / com.artacademy.supply (kiểu cột giống Hibernate tạo ra
-- để ddl-auto=validate chấp nhận). PR của E chưa có migration nên thiếu file này thì backend không chạy được trên MySQL.
-- E nên review lại nội dung; nếu E có bản migration riêng thì dùng bản của E và bỏ file này.
-- course_included_kits.product_id (của B) chưa có khóa ngoại sang products, theo thỏa thuận trong V11.

CREATE TABLE product_categories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    active BIT NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_categories_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sku VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NULL,
    thumbnail_url VARCHAR(500) NULL,
    price BIGINT NOT NULL,
    category_id BIGINT NULL,
    active BIT NOT NULL,
    stock_on_hand INT NOT NULL,
    stock_reserved INT NOT NULL,
    low_stock_threshold INT NOT NULL,
    rating_avg FLOAT(53) NOT NULL,
    rating_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_products_sku UNIQUE (sku),
    KEY ix_products_category (category_id),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES product_categories (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE stock_reservations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id INT NOT NULL,
    user_id INT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    gift BIT NOT NULL,
    status ENUM ('EXPORTED','RELEASED','RESERVED') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reservation_order_product_gift UNIQUE (order_id, product_id, gift),
    KEY ix_stock_reservations_product (product_id),
    CONSTRAINT fk_stock_reservations_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_transactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    type ENUM ('EXPORT','IMPORT','RELEASE','RESERVE','RETURN') NOT NULL,
    quantity INT NOT NULL,
    on_hand_after INT NOT NULL,
    reserved_after INT NOT NULL,
    gift BIT NOT NULL,
    order_id INT NULL,
    shipment_id BIGINT NULL,
    actor_user_id INT NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_inventory_transactions_product (product_id),
    CONSTRAINT fk_inventory_transactions_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE shipments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id INT NOT NULL,
    user_id INT NOT NULL,
    status ENUM ('CANCELLED','DELIVERED','FAILED','PREPARING','RETURNED','SHIPPING') NOT NULL,
    recipient_name VARCHAR(100) NOT NULL,
    recipient_phone VARCHAR(20) NOT NULL,
    shipping_address VARCHAR(500) NOT NULL,
    carrier VARCHAR(100) NULL,
    tracking_code VARCHAR(100) NULL,
    note VARCHAR(500) NULL,
    attempts INT NOT NULL,
    shipped_at DATETIME(6) NULL,
    delivered_at DATETIME(6) NULL,
    failed_at DATETIME(6) NULL,
    returned_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_shipments_order UNIQUE (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE shipment_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shipment_id BIGINT NOT NULL,
    reservation_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    thumbnail_url VARCHAR(500) NULL,
    quantity INT NOT NULL,
    gift BIT NOT NULL,
    PRIMARY KEY (id),
    KEY ix_shipment_items_shipment (shipment_id),
    CONSTRAINT fk_shipment_items_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    user_id INT NOT NULL,
    shipment_id BIGINT NOT NULL,
    rating INT NOT NULL,
    comment VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_product_user UNIQUE (product_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
