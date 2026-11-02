-- Flyway migration generated from the integrated Auth + D JPA mapping proposal.

-- Do not run against a shared database without explicit review and approval.

-- MySQL 8.0.16+ is required for enforced CHECK constraints in V2/V3.

-- Section: Orders, immutable line snapshots and shipping-address snapshot

CREATE TABLE orders (
    id INT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL,
    user_id INT NOT NULL,
    currency VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    payment_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    merchandise_subtotal_vnd BIGINT NOT NULL,
    eligible_subtotal_vnd BIGINT NOT NULL,
    discount_vnd BIGINT NOT NULL,
    shipping_fee_vnd BIGINT NOT NULL,
    total_vnd BIGINT NOT NULL,
    voucher_code VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    voucher_kind VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    voucher_value BIGINT NULL,
    voucher_max_discount_vnd BIGINT NULL,
    payment_expires_at DATETIME(6) NULL COMMENT 'UTC instant stored as DATETIME(6)',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    PRIMARY KEY (id),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_orders_payment_status CHECK (payment_status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'UNKNOWN')),
    KEY ix_orders_owner (user_id, id),
    KEY ix_orders_expiry (status, payment_expires_at),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_items (
    id INT NOT NULL AUTO_INCREMENT,
    order_id INT NOT NULL,
    line_position INT NOT NULL,
    item_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    item_id INT NOT NULL,
    name_snapshot TEXT NOT NULL,
    quantity INT NOT NULL,
    unit_price_vnd BIGINT NOT NULL,
    voucher_eligible BOOLEAN NOT NULL,
    gross_amount_vnd BIGINT NOT NULL,
    allocated_discount_vnd BIGINT NOT NULL,
    net_amount_vnd BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_items_reference UNIQUE (order_id, item_type, item_id),
    CONSTRAINT uk_order_items_position UNIQUE (order_id, line_position),
    CONSTRAINT ck_order_items_values CHECK (quantity > 0 AND item_type IN ('COURSE', 'PRODUCT') AND (item_type <> 'COURSE' OR quantity = 1)),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_shipping_addresses (
    order_id INT NOT NULL,
    recipient TEXT NOT NULL,
    phone TEXT NOT NULL,
    address_line TEXT NOT NULL,
    locality TEXT NOT NULL,
    postal_code TEXT NOT NULL,
    country_code TEXT NOT NULL,
    PRIMARY KEY (order_id),
    CONSTRAINT fk_order_shipping_address_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
