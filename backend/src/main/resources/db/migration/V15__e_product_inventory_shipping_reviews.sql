-- E product, inventory, shipment, and review foundation.
-- Preserves the existing V11 BIGINT product identity and stock_quantity data.
CREATE TABLE product_categories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_categories_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE products
    ADD COLUMN category_id BIGINT NULL,
    ADD COLUMN description VARCHAR(4000) NULL,
    ADD COLUMN stock_on_hand INT NOT NULL DEFAULT 0,
    ADD COLUMN stock_reserved INT NOT NULL DEFAULT 0,
    ADD COLUMN low_stock_threshold INT NOT NULL DEFAULT 5,
    ADD COLUMN rating_avg DOUBLE NOT NULL DEFAULT 0,
    ADD COLUMN rating_count INT NOT NULL DEFAULT 0,
    ADD COLUMN created_at DATETIME(6) NULL,
    ADD COLUMN updated_at DATETIME(6) NULL,
    ADD KEY idx_products_category (category_id),
    ADD CONSTRAINT fk_products_category FOREIGN KEY (category_id)
        REFERENCES product_categories (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_products_stock_on_hand CHECK (stock_on_hand >= 0),
    ADD CONSTRAINT ck_products_stock_reserved CHECK (stock_reserved >= 0 AND stock_reserved <= stock_on_hand),
    ADD CONSTRAINT ck_products_low_stock CHECK (low_stock_threshold >= 0),
    ADD CONSTRAINT ck_products_rating CHECK (rating_avg BETWEEN 0 AND 5 AND rating_count >= 0);

UPDATE products SET stock_on_hand = stock_quantity, stock_reserved = 0;

CREATE TABLE stock_reservations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id INT NOT NULL,
    user_id INT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    gift BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reservation_order_product_gift UNIQUE (order_id, product_id, gift),
    KEY ix_stock_reservations_user (user_id),
    CONSTRAINT fk_stock_reservations_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_reservations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_reservations_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT ck_stock_reservations_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_reservations_status CHECK (status IN ('RESERVED', 'RELEASED', 'EXPORTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE shipments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id INT NOT NULL,
    user_id INT NOT NULL,
    recipient_name VARCHAR(100) NOT NULL,
    recipient_phone VARCHAR(20) NOT NULL,
    shipping_address VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    carrier VARCHAR(100) NULL,
    tracking_code VARCHAR(100) NULL,
    note VARCHAR(500) NULL,
    attempts INT NOT NULL DEFAULT 0,
    shipped_at DATETIME(6) NULL,
    delivered_at DATETIME(6) NULL,
    failed_at DATETIME(6) NULL,
    returned_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_shipments_order UNIQUE (order_id),
    KEY idx_shipments_user (user_id),
    KEY idx_shipments_status (status),
    CONSTRAINT fk_shipments_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shipments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_shipments_status CHECK (status IN ('PREPARING', 'SHIPPING', 'DELIVERED', 'FAILED', 'RETURNED', 'CANCELLED')),
    CONSTRAINT ck_shipments_attempts CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_transactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    quantity INT NOT NULL,
    order_id INT NULL,
    shipment_id BIGINT NULL,
    gift BOOLEAN NOT NULL DEFAULT FALSE,
    on_hand_after INT NOT NULL,
    reserved_after INT NOT NULL,
    note VARCHAR(500) NULL,
    actor_user_id INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_inv_tx_product (product_id, created_at),
    KEY idx_inv_tx_order (order_id),
    CONSTRAINT fk_inv_tx_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inv_tx_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inv_tx_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inv_tx_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_inv_tx_type CHECK (type IN ('IMPORT', 'RESERVE', 'RELEASE', 'EXPORT', 'RETURN')),
    CONSTRAINT ck_inv_tx_quantity CHECK (quantity > 0),
    CONSTRAINT ck_inv_tx_balances CHECK (on_hand_after >= 0 AND reserved_after >= 0 AND reserved_after <= on_hand_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE shipment_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shipment_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    reservation_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    thumbnail_url VARCHAR(500) NULL,
    quantity INT NOT NULL,
    gift BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    KEY ix_shipment_items_reservation (reservation_id),
    CONSTRAINT fk_shipment_items_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shipment_items_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shipment_items_reservation FOREIGN KEY (reservation_id) REFERENCES stock_reservations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_shipment_items_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    user_id INT NOT NULL,
    shipment_id BIGINT NOT NULL,
    rating INT NOT NULL,
    comment VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_product_user UNIQUE (product_id, user_id),
    KEY idx_reviews_product (product_id, created_at),
    CONSTRAINT fk_reviews_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reviews_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reviews_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE RESTRICT,
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;