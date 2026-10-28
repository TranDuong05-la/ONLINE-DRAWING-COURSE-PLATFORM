-- Flyway V8: immutable payment settlement ledger and bounded reconciliation cases.
-- Target MySQL 8.0.16+ (enforced CHECK constraints); do not run before guarded approval.
-- Settlement rows are insert-only application facts; corrections use separate future records.

-- Composite candidate keys let settlement references prove that all three
-- immutable rows belong to the same Order and PaymentAttempt.
ALTER TABLE payment_attempts
    ADD CONSTRAINT uk_payment_attempt_id_order UNIQUE (id, order_id);

ALTER TABLE payment_events
    ADD CONSTRAINT uk_payment_event_id_attempt_order
        UNIQUE (id, attempt_id, order_id);

ALTER TABLE payment_event_conflicts
    ADD CONSTRAINT uk_payment_conflict_id_event_attempt_order
        UNIQUE (id, first_event_id, attempt_id, order_id);

CREATE TABLE payment_settlements (
    id INT NOT NULL AUTO_INCREMENT,
    order_id INT NOT NULL,
    payment_attempt_id INT NOT NULL,
    payment_event_id INT NOT NULL,
    provider_paid_at DATETIME(6) NOT NULL COMMENT 'Verified provider Instant stored as UTC DATETIME(6)',
    settled_at DATETIME(6) NOT NULL COMMENT 'Local settlement Instant stored as UTC DATETIME(6)',
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_settlement_order UNIQUE (order_id),
    CONSTRAINT uk_payment_settlement_attempt UNIQUE (payment_attempt_id),
    CONSTRAINT uk_payment_settlement_event UNIQUE (payment_event_id),
    CONSTRAINT fk_payment_settlement_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_settlement_attempt_order
        FOREIGN KEY (payment_attempt_id, order_id)
        REFERENCES payment_attempts (id, order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_settlement_event_route
        FOREIGN KEY (payment_event_id, payment_attempt_id, order_id)
        REFERENCES payment_events (id, attempt_id, order_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_reconciliation_cases (
    id INT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    event_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    case_type VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    evidence_fingerprint VARCHAR(67) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    payment_event_id INT NULL,
    payment_event_conflict_id INT NULL,
    payment_attempt_id INT NULL,
    order_id INT NULL,
    -- Bounded callback facts are stored only when V5 has no event row,
    -- except exact minor units for a known amount-mismatch case.
    merchant_reference VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    amount_minor_units BIGINT NULL,
    provider_outcome VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    provider_timestamp_state VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    provider_paid_at DATETIME(6) NULL COMMENT 'Verified provider Instant when parseable; UTC DATETIME(6)',
    received_at DATETIME(6) NOT NULL COMMENT 'Local receipt Instant stored as UTC DATETIME(6)',
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    case_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'OPEN',
    resolution_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    resolved_at DATETIME(6) NULL COMMENT 'Local resolution Instant stored as UTC DATETIME(6)',
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_reconciliation_evidence
        UNIQUE (provider, event_reference, case_type, evidence_fingerprint),
    CONSTRAINT uk_payment_reconciliation_conflict UNIQUE (payment_event_conflict_id),
    KEY ix_payment_reconciliation_status_received (case_status, received_at),
    KEY ix_payment_reconciliation_order_status (order_id, case_status),
    CONSTRAINT ck_payment_reconciliation_type CHECK (case_type IN (
        'UNKNOWN_MERCHANT_REFERENCE', 'LATE_SUCCESS', 'AMOUNT_MISMATCH',
        'CURRENCY_MISMATCH', 'CONFLICTING_CALLBACK', 'INVALID_PROVIDER_TIMESTAMP',
        'UNKNOWN_PAYMENT_STATUS', 'ORDER_NOT_PAYABLE', 'SETTLEMENT_CONFLICT',
        'UNUSABLE_TRANSACTION_REFERENCE'
    )),
    CONSTRAINT ck_payment_reconciliation_timestamp_state CHECK (
        provider_timestamp_state IS NULL
        OR provider_timestamp_state IN ('VALID', 'MISSING', 'MALFORMED', 'OUT_OF_RANGE')
    ),
    CONSTRAINT ck_payment_reconciliation_timestamp_value CHECK (
        (provider_timestamp_state IS NULL AND provider_paid_at IS NULL)
        OR (provider_timestamp_state IS NOT NULL
            AND provider_timestamp_state IN ('VALID', 'OUT_OF_RANGE')
            AND provider_paid_at IS NOT NULL)
        OR (provider_timestamp_state IS NOT NULL
            AND provider_timestamp_state IN ('MISSING', 'MALFORMED')
            AND provider_paid_at IS NULL)
    ),
    CONSTRAINT ck_payment_reconciliation_outcome CHECK (
        provider_outcome IS NULL OR provider_outcome IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')
    ),
    CONSTRAINT ck_payment_reconciliation_status CHECK (case_status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT ck_payment_reconciliation_resolution CHECK (
        (case_status = 'OPEN' AND resolution_code IS NULL AND resolved_at IS NULL)
        OR (case_status IN ('RESOLVED', 'DISMISSED')
            AND resolution_code IS NOT NULL AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT ck_payment_reconciliation_route CHECK (
        (
            case_type = 'UNKNOWN_MERCHANT_REFERENCE'
            AND payment_event_id IS NULL AND payment_event_conflict_id IS NULL
            AND payment_attempt_id IS NULL AND order_id IS NULL
            AND merchant_reference IS NOT NULL
            AND amount_minor_units IS NOT NULL AND provider_outcome IS NOT NULL
            AND payload_sha256 IS NOT NULL AND provider_timestamp_state IS NOT NULL
        )
        OR
        (
            case_type = 'UNUSABLE_TRANSACTION_REFERENCE'
            AND payment_event_id IS NULL AND payment_event_conflict_id IS NULL
            AND payment_attempt_id IS NOT NULL AND order_id IS NOT NULL
            AND merchant_reference IS NOT NULL
            AND amount_minor_units IS NOT NULL AND provider_outcome IS NOT NULL
            AND payload_sha256 IS NOT NULL
            AND provider_timestamp_state IS NOT NULL
        )
        OR
        (
            case_type NOT IN ('UNKNOWN_MERCHANT_REFERENCE', 'UNUSABLE_TRANSACTION_REFERENCE')
            AND payment_event_id IS NOT NULL AND payment_attempt_id IS NOT NULL AND order_id IS NOT NULL
            AND merchant_reference IS NULL AND provider_outcome IS NULL
            AND payload_sha256 IS NULL
            AND provider_timestamp_state IS NULL
            AND (
                (case_type = 'AMOUNT_MISMATCH' AND amount_minor_units IS NOT NULL)
                OR (case_type <> 'AMOUNT_MISMATCH' AND amount_minor_units IS NULL)
            )
            AND (
                (case_type = 'CONFLICTING_CALLBACK' AND payment_event_conflict_id IS NOT NULL)
                OR (case_type <> 'CONFLICTING_CALLBACK' AND payment_event_conflict_id IS NULL)
            )
        )
    ),
    CONSTRAINT ck_payment_reconciliation_amount CHECK (amount_minor_units IS NULL OR amount_minor_units >= 0),
    CONSTRAINT fk_payment_reconciliation_attempt
        FOREIGN KEY (payment_attempt_id) REFERENCES payment_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_reconciliation_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_reconciliation_event
        FOREIGN KEY (payment_event_id) REFERENCES payment_events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_reconciliation_conflict
        FOREIGN KEY (payment_event_conflict_id) REFERENCES payment_event_conflicts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_reconciliation_event_route
        FOREIGN KEY (payment_event_id, payment_attempt_id, order_id)
        REFERENCES payment_events (id, attempt_id, order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_reconciliation_conflict_route
        FOREIGN KEY (payment_event_conflict_id, payment_event_id, payment_attempt_id, order_id)
        REFERENCES payment_event_conflicts (id, first_event_id, attempt_id, order_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
