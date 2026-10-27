-- Flyway migration generated from the integrated Auth + D JPA mapping proposal.

-- Do not run against a shared database without explicit review and approval.

-- MySQL 8.0.16+ is required for enforced CHECK constraints in V2/V3.

-- Section: Cart

CREATE TABLE carts (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    PRIMARY KEY (id),
    CONSTRAINT uk_carts_user UNIQUE (user_id),
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE cart_items (
    id INT NOT NULL AUTO_INCREMENT,
    cart_id INT NOT NULL,
    item_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    item_id INT NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_cart_items_reference UNIQUE (cart_id, item_type, item_id),
    CONSTRAINT ck_cart_items_values CHECK (
        quantity > 0 AND item_type IN ('COURSE', 'PRODUCT') AND (item_type <> 'COURSE' OR quantity = 1)
    ),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
