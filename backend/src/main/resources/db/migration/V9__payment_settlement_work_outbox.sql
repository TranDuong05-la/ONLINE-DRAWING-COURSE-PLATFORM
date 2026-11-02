-- Flyway V9: durable local settlement work and audited operator actions.
-- No legacy PaymentEvents are backfilled. Only verified eligible ingress may enqueue work.
-- MySQL 8.0.16+ is required for enforced CHECK constraints.

CREATE TABLE payment_settlement_work_items (
    id INT NOT NULL AUTO_INCREMENT,
    payment_event_id INT NOT NULL,
    state VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    retry_generation BIGINT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NULL COMMENT 'UTC instant; next eligible claim time',
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL COMMENT 'UTC lease expiry',
    last_claimed_at DATETIME(6) NULL,
    last_failure_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_settlement_work_event UNIQUE (payment_event_id),
    KEY ix_payment_settlement_work_due (state, available_at, id),
    KEY ix_payment_settlement_work_lease (state, lease_until, id),
    KEY ix_payment_settlement_work_review (state, updated_at, id),
    CONSTRAINT ck_payment_settlement_work_state CHECK (
        state IN ('READY', 'RETRY_WAIT', 'LEASED', 'COMPLETED', 'MANUAL_REVIEW', 'CLOSED_NOT_ELIGIBLE')
    ),
    CONSTRAINT ck_payment_settlement_work_attempts CHECK (attempt_count BETWEEN 0 AND 8),
    CONSTRAINT ck_payment_settlement_work_generation CHECK (retry_generation >= 0),
    CONSTRAINT ck_payment_settlement_work_failure CHECK (
        last_failure_code IS NULL OR last_failure_code IN (
            'TRANSIENT_DATABASE', 'DEADLOCK', 'LOCK_TIMEOUT', 'LEASE_EXPIRED',
            'ATTEMPTS_EXHAUSTED', 'EVENT_NOT_ELIGIBLE', 'ORDER_NOT_ELIGIBLE',
            'SETTLEMENT_CONFLICT', 'PARTICIPANT_REJECTED', 'UNCLASSIFIED_FAILURE'
        )
    ),
    CONSTRAINT ck_payment_settlement_work_fields CHECK (
        (
            state IN ('READY', 'RETRY_WAIT') AND available_at IS NOT NULL
            AND lease_token IS NULL AND lease_owner IS NULL AND lease_until IS NULL
            AND completed_at IS NULL
        ) OR (
            state = 'LEASED' AND available_at IS NULL
            AND lease_token IS NOT NULL AND lease_owner IS NOT NULL AND lease_until IS NOT NULL
            AND completed_at IS NULL
        ) OR (
            state IN ('MANUAL_REVIEW', 'CLOSED_NOT_ELIGIBLE') AND available_at IS NULL
            AND lease_token IS NULL AND lease_owner IS NULL AND lease_until IS NULL
            AND completed_at IS NULL
        ) OR (
            state = 'COMPLETED' AND available_at IS NULL
            AND lease_token IS NULL AND lease_owner IS NULL AND lease_until IS NULL
            AND completed_at IS NOT NULL
        )
    ),
    CONSTRAINT fk_payment_settlement_work_event
        FOREIGN KEY (payment_event_id) REFERENCES payment_events (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_settlement_work_actions (
    id INT NOT NULL AUTO_INCREMENT,
    work_item_id INT NOT NULL,
    action_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_type VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    from_state VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    to_state VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    actor_user_id INT NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    prior_attempt_count SMALLINT NOT NULL,
    prior_retry_generation BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_settlement_work_action_key UNIQUE (work_item_id, action_key),
    KEY ix_payment_settlement_work_action_history (work_item_id, id),
    CONSTRAINT ck_payment_settlement_work_action CHECK (
        (action_type = 'REQUEUE' AND from_state = 'MANUAL_REVIEW' AND to_state = 'READY'
            AND reason_code IN ('TRANSIENT_CAUSE_RESOLVED', 'ADAPTER_RESTORED', 'EVIDENCE_REVERIFIED'))
        OR
        (action_type = 'CLOSE_NOT_ELIGIBLE' AND from_state = 'MANUAL_REVIEW'
            AND to_state = 'CLOSED_NOT_ELIGIBLE' AND reason_code = 'REVIEWED_NOT_ELIGIBLE')
    ),
    CONSTRAINT ck_payment_settlement_work_action_attempts CHECK (prior_attempt_count BETWEEN 0 AND 8),
    CONSTRAINT ck_payment_settlement_work_action_generation CHECK (prior_retry_generation >= 0),
    CONSTRAINT fk_payment_settlement_work_action_item
        FOREIGN KEY (work_item_id) REFERENCES payment_settlement_work_items (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_settlement_work_action_actor
        FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
