-- Flyway migration generated from the integrated Auth + D JPA mapping proposal.

-- Do not run against a shared database without explicit review and approval.

-- MySQL 8.0.16+ is required for enforced CHECK constraints in V2/V3.

-- Section: Voucher definitions, usage counters and canonical claims

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
