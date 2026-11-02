-- Durable Refund persistence foundation. No API, provider execution, or runtime activation.
-- MySQL 8.0.16+ is required for enforced CHECK constraints.

ALTER TABLE orders
    ADD CONSTRAINT uk_orders_id_user UNIQUE (id, user_id);

ALTER TABLE order_items
    ADD CONSTRAINT uk_order_items_id_order UNIQUE (id, order_id);

ALTER TABLE payment_settlements
    ADD CONSTRAINT uk_payment_settlements_id_order UNIQUE (id, order_id);

CREATE TABLE refund_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL,
    user_id INT NOT NULL,
    order_id INT NOT NULL,
    order_item_id INT NOT NULL,
    payment_settlement_id INT NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_type VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    customer_note TEXT NULL,
    item_type_snapshot VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    item_quantity_snapshot INT NOT NULL,
    item_gross_amount_vnd BIGINT NOT NULL,
    item_discount_vnd BIGINT NOT NULL,
    item_net_amount_vnd BIGINT NOT NULL,
    item_voucher_eligible BOOLEAN NOT NULL,
    requested_quantity INT NOT NULL,
    requested_amount_vnd BIGINT NOT NULL,
    currency VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    captured_amount_vnd_snapshot BIGINT NOT NULL,
    provider_paid_at DATETIME(6) NOT NULL COMMENT 'Verified provider Instant stored as UTC DATETIME(6)',
    settled_at DATETIME(6) NOT NULL COMMENT 'Committed settlement Instant stored as UTC DATETIME(6)',
    amount_reservation_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    decision_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    financial_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    course_access_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    physical_effect_required BOOLEAN NOT NULL,
    physical_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    shipment_hold_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    course_evidence_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    course_evidence_version VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    course_evidence_observed_at DATETIME(6) NULL,
    course_revalidation_fence VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    shipment_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    shipment_hold_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    product_evidence_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    product_evidence_observed_at DATETIME(6) NULL,
    decided_by_user_id INT NULL,
    requested_at DATETIME(6) NOT NULL,
    decision_at DATETIME(6) NULL,
    financial_executed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_request_owner_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT uk_refund_request_id_item UNIQUE (id, order_item_id),
    KEY ix_refund_request_owner_history (user_id, requested_at, id),
    KEY ix_refund_request_order_decision (order_id, decision_status, id),
    KEY ix_refund_request_item_reservation (order_item_id, amount_reservation_status, id),
    KEY ix_refund_request_settlement_reservation (payment_settlement_id, amount_reservation_status, id),
    KEY ix_refund_request_financial_recovery (financial_status, updated_at, id),
    CONSTRAINT ck_refund_request_scope CHECK (scope_type IN ('STANDALONE_COURSE', 'PRODUCT_UNITS', 'WHOLE_COMBO')),
    CONSTRAINT ck_refund_request_item_type CHECK (item_type_snapshot IN ('COURSE', 'PRODUCT', 'COMBO')),
    CONSTRAINT ck_refund_request_amounts CHECK (
        item_quantity_snapshot > 0
        AND item_gross_amount_vnd >= 0
        AND item_discount_vnd >= 0
        AND item_net_amount_vnd >= 0
        AND item_discount_vnd <= item_gross_amount_vnd
        AND item_net_amount_vnd = item_gross_amount_vnd - item_discount_vnd
        AND requested_quantity > 0
        AND requested_amount_vnd > 0
        AND captured_amount_vnd_snapshot > 0
        AND requested_amount_vnd <= item_net_amount_vnd
        AND requested_amount_vnd <= captured_amount_vnd_snapshot
    ),
    CONSTRAINT ck_refund_request_scope_values CHECK (
        (scope_type = 'PRODUCT_UNITS' AND item_type_snapshot = 'PRODUCT'
            AND requested_quantity <= item_quantity_snapshot)
        OR (scope_type = 'STANDALONE_COURSE' AND item_type_snapshot = 'COURSE'
            AND item_quantity_snapshot = 1 AND requested_quantity = 1
            AND requested_amount_vnd = item_net_amount_vnd)
        OR (scope_type = 'WHOLE_COMBO' AND item_type_snapshot = 'COMBO'
            AND item_quantity_snapshot = 1 AND requested_quantity = 1
            AND item_voucher_eligible = FALSE AND item_discount_vnd = 0
            AND item_gross_amount_vnd = item_net_amount_vnd
            AND requested_amount_vnd = item_net_amount_vnd)
    ),
    CONSTRAINT ck_refund_request_reservation CHECK (amount_reservation_status IN ('RESERVED', 'CONSUMED', 'RELEASED')),
    CONSTRAINT ck_refund_request_decision CHECK (decision_status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_refund_request_financial CHECK (financial_status IN ('NOT_STARTED', 'READY', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'REQUIRES_REVIEW')),
    CONSTRAINT ck_refund_request_course_access CHECK (course_access_status IN ('NOT_APPLICABLE', 'UNCHANGED', 'ELIGIBILITY_CONFIRMED', 'REVOCATION_PENDING', 'REVOKED', 'REQUIRES_REVIEW')),
    CONSTRAINT ck_refund_request_physical CHECK (physical_status IN ('NOT_REQUIRED', 'PENDING', 'ADJUSTED', 'RELEASED', 'REQUIRES_REVIEW')),
    CONSTRAINT ck_refund_request_hold CHECK (shipment_hold_status IN ('NOT_APPLICABLE', 'ACTIVE', 'RELEASED', 'REQUIRES_REVIEW')),
    CONSTRAINT fk_refund_request_owner_route FOREIGN KEY (order_id, user_id)
        REFERENCES orders (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_request_item_route FOREIGN KEY (order_item_id, order_id)
        REFERENCES order_items (id, order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_request_settlement_route FOREIGN KEY (payment_settlement_id, order_id)
        REFERENCES payment_settlements (id, order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_request_decider FOREIGN KEY (decided_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refund_unit_allocations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_request_id BIGINT NOT NULL,
    order_item_id INT NOT NULL,
    unit_position INT NOT NULL,
    allocated_amount_vnd BIGINT NOT NULL,
    allocation_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    active_marker TINYINT NULL,
    created_at DATETIME(6) NOT NULL,
    released_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_unit_request_position UNIQUE (refund_request_id, unit_position),
    CONSTRAINT uk_refund_unit_active_position UNIQUE (order_item_id, unit_position, active_marker),
    KEY ix_refund_unit_request (refund_request_id, unit_position, id),
    CONSTRAINT ck_refund_unit_values CHECK (unit_position >= 0 AND allocated_amount_vnd >= 0),
    CONSTRAINT ck_refund_unit_lifecycle CHECK (
        (allocation_status IN ('RESERVED', 'REFUNDED') AND active_marker = 1 AND released_at IS NULL)
        OR (allocation_status = 'RELEASED' AND active_marker IS NULL AND released_at IS NOT NULL)
    ),
    CONSTRAINT fk_refund_unit_request_item FOREIGN KEY (refund_request_id, order_item_id)
        REFERENCES refund_requests (id, order_item_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refund_provider_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL,
    refund_request_id BIGINT NOT NULL,
    attempt_number INT NOT NULL,
    provider VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    provider_operation_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    provider_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    amount_vnd BIGINT NOT NULL,
    currency VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    attempt_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    execution_slot TINYINT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidence_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    started_at DATETIME(6) NULL,
    evidence_observed_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    retry_authorized_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_attempt_number UNIQUE (refund_request_id, attempt_number),
    CONSTRAINT uk_refund_attempt_execution_slot UNIQUE (refund_request_id, execution_slot),
    CONSTRAINT uk_refund_attempt_operation UNIQUE (provider, provider_operation_key),
    CONSTRAINT uk_refund_attempt_provider_reference UNIQUE (provider, provider_reference),
    CONSTRAINT uk_refund_attempt_id_request UNIQUE (id, refund_request_id),
    KEY ix_refund_attempt_recovery (attempt_status, updated_at, id),
    CONSTRAINT ck_refund_attempt_values CHECK (attempt_number > 0 AND amount_vnd > 0 AND (execution_slot IS NULL OR execution_slot = 1)),
    CONSTRAINT ck_refund_attempt_status CHECK (attempt_status IN ('PENDING', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'DEFINITIVE_FAILURE', 'REQUIRES_REVIEW')),
    CONSTRAINT ck_refund_attempt_slot CHECK (
        (attempt_status IN ('PENDING', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'REQUIRES_REVIEW') AND execution_slot = 1)
        OR (attempt_status = 'DEFINITIVE_FAILURE')
    ),
    CONSTRAINT fk_refund_attempt_request FOREIGN KEY (refund_request_id)
        REFERENCES refund_requests (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refund_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_request_id BIGINT NOT NULL,
    provider_attempt_id BIGINT NULL,
    operation_type VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    event_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    event_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_outcome VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    evidence_source VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    evidence_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    evidence_version VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    evidence_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    state_before_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_after_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NULL,
    evidence_observed_at DATETIME(6) NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_event_identity UNIQUE (refund_request_id, operation_type, event_key),
    CONSTRAINT uk_refund_event_id_request UNIQUE (id, refund_request_id),
    KEY ix_refund_event_request_time (refund_request_id, recorded_at, id),
    CONSTRAINT ck_refund_event_outcome CHECK (event_outcome IN ('APPLIED', 'ALREADY_APPLIED', 'REJECTED')),
    CONSTRAINT fk_refund_event_request FOREIGN KEY (refund_request_id)
        REFERENCES refund_requests (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_event_attempt_route FOREIGN KEY (provider_attempt_id, refund_request_id)
        REFERENCES refund_provider_attempts (id, refund_request_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refund_review_cases (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL,
    refund_request_id BIGINT NOT NULL,
    provider_attempt_id BIGINT NULL,
    original_event_id BIGINT NULL,
    case_type VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    case_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    original_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    conflicting_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidence_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    evidence_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    details_snapshot TEXT NULL,
    opened_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    resolution_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    resolved_by_user_id INT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_review_case_key UNIQUE (refund_request_id, case_key),
    KEY ix_refund_review_queue (case_status, opened_at, id),
    CONSTRAINT ck_refund_review_status CHECK (case_status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT ck_refund_review_resolution CHECK (
        (case_status = 'OPEN' AND resolved_at IS NULL AND resolution_code IS NULL AND resolved_by_user_id IS NULL)
        OR (case_status IN ('RESOLVED', 'DISMISSED') AND resolved_at IS NOT NULL AND resolution_code IS NOT NULL)
    ),
    CONSTRAINT fk_refund_review_request FOREIGN KEY (refund_request_id)
        REFERENCES refund_requests (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_review_attempt_route FOREIGN KEY (provider_attempt_id, refund_request_id)
        REFERENCES refund_provider_attempts (id, refund_request_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_review_event_route FOREIGN KEY (original_event_id, refund_request_id)
        REFERENCES refund_events (id, refund_request_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_review_resolver FOREIGN KEY (resolved_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
