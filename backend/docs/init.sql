-- Legacy manual SQL reference, retained for copy/paste review only.
-- Prefer the ordered Flyway migrations in src/main/resources/db/migration for a new local database.
-- Do not run this script on a database managed by Flyway: the migrations will fail on existing tables.
-- Not an executable migration and not tested against MySQL.
-- It contains only the integrated Auth + D tables; no Course/Product/Enrollment/Inventory/Checkout tables.
SET NAMES utf8mb4;
SELECT DATABASE() AS selected_database;

CREATE TABLE users (
    id INT NOT NULL AUTO_INCREMENT,
    full_name VARCHAR(100) NOT NULL,
    email VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NULL,
    role VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    avatar_url VARCHAR(255) NULL,
    google_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    is_active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    updated_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'INSTRUCTOR', 'STAFF', 'ADMIN')),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_google_id UNIQUE (google_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_profiles (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    profile_type VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(255) NULL,
    age_group VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    bio TEXT NULL,
    signature_image_url VARCHAR(255) NULL,
    is_approved BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    KEY ix_user_profiles_user (user_id),
    CONSTRAINT ck_user_profiles_type CHECK (profile_type IN ('LEARNER', 'INSTRUCTOR')),
    CONSTRAINT ck_user_profiles_age_group CHECK (age_group IS NULL OR age_group IN ('KIDS', 'TEEN', 'ADULT', 'SENIOR')),
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_addresses (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    receiver_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    province VARCHAR(100) NOT NULL,
    district VARCHAR(100) NOT NULL,
    ward VARCHAR(100) NOT NULL,
    street_address TEXT NOT NULL,
    is_default BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    KEY ix_user_addresses_user (user_id),
    CONSTRAINT fk_user_addresses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE carts (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    PRIMARY KEY (id),
    CONSTRAINT uk_carts_user UNIQUE (user_id),
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

CREATE TABLE vouchers (
    id INT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL,
    definition_version BIGINT NOT NULL,
    code VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    kind VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    discount_value BIGINT NOT NULL,
    percentage_cap_vnd BIGINT NULL,
    active BOOLEAN NOT NULL,
    valid_from DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    valid_until DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    minimum_subtotal_vnd BIGINT NOT NULL,
    total_usage_limit BIGINT NULL,
    per_user_usage_limit BIGINT NULL,
    reserved_count BIGINT NOT NULL,
    redeemed_count BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    PRIMARY KEY (id),
    CONSTRAINT ck_vouchers_kind CHECK (kind IN ('FIXED_VND', 'PERCENT_BASIS_POINTS')),
    CONSTRAINT uk_vouchers_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE voucher_user_usage (
    id INT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL,
    voucher_id INT NOT NULL,
    user_id INT NOT NULL,
    reserved_count BIGINT NOT NULL,
    redeemed_count BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_voucher_user_usage UNIQUE (voucher_id, user_id),
    CONSTRAINT fk_voucher_user_usage_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_voucher_user_usage_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE voucher_claims (
    id INT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL,
    claim_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    voucher_id INT NOT NULL,
    definition_version BIGINT NOT NULL,
    user_id INT NOT NULL,
    order_id INT NOT NULL,
    code_snapshot VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    kind_snapshot VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    value_snapshot BIGINT NOT NULL,
    cap_snapshot_vnd BIGINT NOT NULL,
    eligible_subtotal_vnd BIGINT NOT NULL,
    discount_vnd BIGINT NOT NULL,
    order_created_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    expires_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    reserved_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    state VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    transitioned_at DATETIME(6) NULL COMMENT 'UTC instant stored as DATETIME(6)',
    payment_reference TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    paid_at DATETIME(6) NULL COMMENT 'UTC instant stored as DATETIME(6)',
    release_reason VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_voucher_claim_kind CHECK (kind_snapshot IN ('FIXED_VND', 'PERCENT_BASIS_POINTS')),
    CONSTRAINT ck_voucher_claim_state CHECK (state IN ('RESERVED', 'REDEEMED', 'RELEASED')),
    CONSTRAINT ck_voucher_claim_release_reason CHECK (release_reason IS NULL OR release_reason IN ('CANCELLED', 'EXPIRED', 'FINAL_FAILURE')),
    CONSTRAINT uk_voucher_claim_key UNIQUE (claim_key),
    CONSTRAINT uk_voucher_claim_order UNIQUE (order_id),
    KEY ix_voucher_claim_expiry (state, expires_at),
    CONSTRAINT fk_voucher_claim_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_voucher_claim_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_voucher_claim_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_attempts (
    id INT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL,
    order_id INT NOT NULL,
    user_id INT NOT NULL,
    provider VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    merchant_reference VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    expected_amount_vnd BIGINT NOT NULL,
    currency VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    order_created_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    expires_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    transaction_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    succeeded_at DATETIME(6) NULL COMMENT 'UTC instant stored as DATETIME(6)',
    review_reason VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_payment_attempt_status CHECK (status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'UNKNOWN')),
    CONSTRAINT ck_payment_review_reason CHECK (review_reason IN ('NONE', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH', 'ROUTING_MISMATCH', 'LATE_SUCCESS', 'ORDER_NOT_PAYABLE', 'CONFLICTING_SUCCESS', 'INVALID_EVENT_TIME', 'SETTLEMENT_CONFLICT', 'VOUCHER_CLAIM_MISMATCH')),
    CONSTRAINT uk_payment_merchant UNIQUE (provider, merchant_reference),
    CONSTRAINT uk_payment_transaction UNIQUE (provider, transaction_reference),
    KEY ix_payment_order_owner (order_id, user_id),
    CONSTRAINT fk_payment_attempt_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_attempt_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_events (
    id INT NOT NULL AUTO_INCREMENT,
    attempt_id INT NOT NULL,
    order_id INT NOT NULL,
    user_id INT NOT NULL,
    provider VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    event_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    merchant_reference VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    transaction_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    amount_vnd BIGINT NOT NULL,
    currency VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    outcome VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    received_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    payload_sha256 VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    verification_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    fingerprint VARCHAR(67) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    before_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    after_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    disposition VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    reason VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    observed_order_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_payment_event_outcome CHECK (outcome IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
    CONSTRAINT ck_payment_event_before_status CHECK (before_status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'UNKNOWN')),
    CONSTRAINT ck_payment_event_after_status CHECK (after_status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'UNKNOWN')),
    CONSTRAINT ck_payment_event_disposition CHECK (disposition IN ('APPLIED', 'IGNORED', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_payment_event_reason CHECK (reason IN ('NONE', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH', 'ROUTING_MISMATCH', 'LATE_SUCCESS', 'ORDER_NOT_PAYABLE', 'CONFLICTING_SUCCESS', 'INVALID_EVENT_TIME', 'SETTLEMENT_CONFLICT', 'VOUCHER_CLAIM_MISMATCH')),
    CONSTRAINT ck_payment_event_order_status CHECK (observed_order_status IN ('PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT uk_payment_event UNIQUE (provider, event_reference),
    KEY ix_payment_event_attempt_owner (attempt_id, user_id),
    CONSTRAINT fk_payment_event_attempt FOREIGN KEY (attempt_id) REFERENCES payment_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_event_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_event_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_event_conflicts (
    id INT NOT NULL AUTO_INCREMENT,
    first_event_id INT NOT NULL,
    attempt_id INT NOT NULL,
    order_id INT NOT NULL,
    user_id INT NOT NULL,
    provider VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    event_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    first_fingerprint VARCHAR(67) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    conflicting_fingerprint VARCHAR(67) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    merchant_reference VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    transaction_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    amount_vnd BIGINT NOT NULL,
    currency VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    outcome VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    received_at DATETIME(6) NOT NULL COMMENT 'UTC instant stored as DATETIME(6)',
    payload_sha256 VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    verification_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    observed_order_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_payment_conflict_outcome CHECK (outcome IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
    CONSTRAINT ck_payment_conflict_order_status CHECK (observed_order_status IN ('PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT uk_payment_event_conflict UNIQUE (first_event_id, conflicting_fingerprint),
    KEY ix_payment_event_conflict_owner (user_id, order_id),
    CONSTRAINT fk_payment_conflict_first_event FOREIGN KEY (first_event_id) REFERENCES payment_events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_conflict_attempt FOREIGN KEY (attempt_id) REFERENCES payment_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_conflict_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_conflict_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
