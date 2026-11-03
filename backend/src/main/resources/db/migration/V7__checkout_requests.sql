-- Durable, owner-scoped Checkout idempotency claims.
-- Processing rows and Order creation must be committed by the same caller transaction.
CREATE TABLE checkout_requests (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    request_fingerprint VARCHAR(67) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    order_id INT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_checkout_requests_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT uk_checkout_requests_order UNIQUE (order_id),
    CONSTRAINT fk_checkout_requests_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_checkout_requests_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT,
    CONSTRAINT ck_checkout_requests_state CHECK (state IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_checkout_requests_completion CHECK (
        (state = 'PROCESSING' AND order_id IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND order_id IS NOT NULL AND completed_at IS NOT NULL)
    ),
    INDEX ix_checkout_requests_state_updated (state, updated_at)
) ENGINE=InnoDB;
