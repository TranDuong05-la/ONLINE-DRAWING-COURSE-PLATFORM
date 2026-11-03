# Refund Persistence Schema Proposal

**Status:** Design/review only. This is proposed SQL documentation, not a Flyway migration. No refund schema is implemented. The D-Refund-2B policies and D-Refund-3B1 state machine are pure advisory functions; they do not persist requests, reserve units/amounts, or acquire Member E holds.

## Source-grounded baseline

Verified in the current branch:

- `OrderItemJpaEntity` contains immutable whole-VND snapshots for item type/ID, quantity, unit price, gross, allocated voucher discount, and net amount. `OrderItem.netAmountForUnits(firstUnit, unitCount)` calculates a deterministic net amount for a contiguous zero-based unit range, assigning the remainder to the earliest positions.
- `PaymentSettlementJpaEntity` is insert-only by application convention and has unique Order, PaymentAttempt, and PaymentEvent references. V8 makes settlement one-per-Order. It must not be updated to represent refunds.
- Payment attempts/events and V8 reconciliation cases preserve payment evidence; they do not represent refunds.
- Checkout idempotency in V7 uses owner/key uniqueness and a request fingerprint. Voucher claims in V4 use per-Order uniqueness and usage counters.
- `RefundAmountCalculator` and `RefundEligibilityPolicy` are pure code. The calculator rejects out-of-range/overlapping requested and unavailable unit ranges and uses the immutable OrderItem net amount. It cannot prevent concurrent allocation.
- V1鈥揤8 contain no refund tables and no Member E Shipment, packing, inventory, or handover tables. Existing `InventoryReservationPort` is an interface, not an integrated E implementation.
- Spring Boot uses the application datasource configured by `DB_URL` and the auto-configured JPA transaction manager. Same-datasource participation by future E persistence is unverified.

Relevant files include `entity/OrderItem.java`, `entity/OrderItemJpaEntity.java`, `entity/PaymentSettlementJpaEntity.java`, `entity/PaymentAttemptJpaEntity.java`, `entity/CheckoutRequestJpaEntity.java`, `entity/VoucherClaimJpaEntity.java`, `entity/RefundAmountCalculator.java`, `entity/RefundEligibilityPolicy.java`, and migrations V3鈥揤8.

## Recommended model

Use four D-owned tables:

1. `refund_requests`: one customer request for one OrderItem, keyed idempotently by requester and request key. The request-level status represents the Admin decision only.
2. `refund_items`: the immutable OrderItem amount/quantity snapshot and two independent workflow axes: physical adjustment and financial refund. One row per request in the initial policy; it can be generalized only after multi-item requests are approved.
3. `refund_unit_allocations`: one durable slot per logical Product unit position. The primary key `(order_item_id, unit_position)` makes a unit鈥檚 current allocation unique. Free slots can be reallocated after a safely rejected/cancelled request; successful refund slots remain permanently consumed.
4. `refund_provider_attempts`: provider execution attempts for one approved refund obligation. Unknown results retain the same obligation and provider reference; they do not authorize a new independent refund.

Also add `refund_events`, an append-only D-owned audit trail. It preserves decision, hold/fulfillment, and provider outcome transitions without mutating the immutable payment settlement or storing raw callback URLs/secrets. Its composite foreign keys must prove that the item and optional provider attempt belong to the event's request.

No Shipment/Inventory tables are proposed here. E must provide its real shipment/hold contract and persistence before D can join it.

## Proposed MySQL 8 DDL (documentation only)

Assumptions: MySQL 8.0.16+ for enforced CHECK constraints; InnoDB; identifiers use case-sensitive binary collations; all `DATETIME(6)` values represent UTC instants. Names are proposed and must be checked against the actual target branch鈥檚 naming conventions before a migration is approved.

The composite candidate keys below permit owner, OrderItem, request/item, and provider-attempt/event route consistency in foreign keys. If the existing schema already has equivalent keys, do not add duplicates.

```sql
ALTER TABLE orders
    ADD CONSTRAINT uk_orders_id_user UNIQUE (id, user_id);

ALTER TABLE order_items
    ADD CONSTRAINT uk_order_items_id_order UNIQUE (id, order_id);

CREATE TABLE refund_requests (
    id INT NOT NULL AUTO_INCREMENT,
    order_id INT NOT NULL,
    user_id INT NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    customer_note VARCHAR(1000) NULL,
    decision_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'REQUESTED',
    requested_at DATETIME(6) NOT NULL COMMENT 'UTC instant',
    reviewed_by_user_id INT NULL,
    reviewed_at DATETIME(6) NULL COMMENT 'UTC instant',
    decision_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC instant',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC instant',
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_request_owner_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT uk_refund_request_id_order UNIQUE (id, order_id),
    KEY ix_refund_request_order_status (order_id, decision_status, requested_at),
    KEY ix_refund_request_user_status (user_id, decision_status, requested_at),
    CONSTRAINT ck_refund_request_fingerprint CHECK (CHAR_LENGTH(request_fingerprint) = 64),
    CONSTRAINT ck_refund_request_decision CHECK (decision_status IN ('REQUESTED','APPROVED','REJECTED')),
    CONSTRAINT ck_refund_request_review CHECK (
        (decision_status = 'REQUESTED' AND reviewed_by_user_id IS NULL AND reviewed_at IS NULL AND decision_code IS NULL)
        OR (decision_status IN ('APPROVED','REJECTED') AND reviewed_by_user_id IS NOT NULL
            AND reviewed_at IS NOT NULL AND decision_code IS NOT NULL)
    ),
    CONSTRAINT fk_refund_request_order_owner FOREIGN KEY (order_id, user_id)
        REFERENCES orders (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_request_settled_order FOREIGN KEY (order_id)
        REFERENCES payment_settlements (order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_request_reviewer FOREIGN KEY (reviewed_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refund_items (
    id INT NOT NULL AUTO_INCREMENT,
    refund_request_id INT NOT NULL,
    order_id INT NOT NULL,
    order_item_id INT NOT NULL,
    item_type_snapshot VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    item_quantity_snapshot INT NOT NULL,
    gross_amount_vnd_snapshot BIGINT NOT NULL,
    allocated_discount_vnd_snapshot BIGINT NOT NULL,
    net_amount_vnd_snapshot BIGINT NOT NULL,
    requested_quantity INT NOT NULL,
    requested_amount_vnd BIGINT NOT NULL,
    approved_quantity INT NULL,
    approved_amount_vnd BIGINT NULL,
    shipment_reference VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    shipment_hold_reference VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    shipment_hold_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    fulfillment_status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    financial_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    review_required BOOLEAN NOT NULL DEFAULT FALSE,
    review_reason_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC instant',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC instant',
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_item_request UNIQUE (refund_request_id),
    CONSTRAINT uk_refund_item_id_order_item UNIQUE (id, order_item_id),
    CONSTRAINT uk_refund_item_id_request UNIQUE (id, refund_request_id),
    KEY ix_refund_item_order_item_financial (order_item_id, financial_status),
    KEY ix_refund_item_order_fulfillment (order_id, fulfillment_status),
    CONSTRAINT ck_refund_item_type CHECK (item_type_snapshot IN ('COURSE','PRODUCT')),
    CONSTRAINT ck_refund_item_amounts CHECK (
        item_quantity_snapshot > 0 AND gross_amount_vnd_snapshot >= 0
        AND allocated_discount_vnd_snapshot >= 0
        AND allocated_discount_vnd_snapshot <= gross_amount_vnd_snapshot
        AND net_amount_vnd_snapshot = gross_amount_vnd_snapshot - allocated_discount_vnd_snapshot
        AND requested_quantity > 0 AND requested_quantity <= item_quantity_snapshot
        AND requested_amount_vnd > 0 AND requested_amount_vnd <= net_amount_vnd_snapshot
        AND (approved_quantity IS NULL OR approved_quantity = requested_quantity)
        AND (approved_amount_vnd IS NULL OR approved_amount_vnd = requested_amount_vnd)
        AND (item_type_snapshot <> 'COURSE' OR (item_quantity_snapshot = 1 AND requested_quantity = 1))
    ),
    CONSTRAINT ck_refund_item_fulfillment CHECK (
        fulfillment_status IN ('NOT_REQUIRED','PENDING','ADJUSTED','RELEASED','REQUIRES_REVIEW')
    ),
    CONSTRAINT ck_refund_item_shipment_route CHECK (
        (item_type_snapshot = 'COURSE' AND fulfillment_status = 'NOT_REQUIRED'
            AND shipment_hold_status = 'NOT_APPLICABLE'
            AND shipment_reference IS NULL AND shipment_hold_reference IS NULL)
        OR (item_type_snapshot = 'PRODUCT' AND fulfillment_status <> 'NOT_REQUIRED'
            AND shipment_hold_status IN ('ACTIVE','RELEASED','REQUIRES_REVIEW')
            AND shipment_reference IS NOT NULL AND shipment_hold_reference IS NOT NULL)
    ),
    CONSTRAINT ck_refund_item_hold_status CHECK (
        shipment_hold_status IN ('NOT_APPLICABLE','ACTIVE','RELEASED','REQUIRES_REVIEW')
    ),
    CONSTRAINT ck_refund_item_review CHECK (
        (review_required = FALSE AND review_reason_code IS NULL)
        OR (review_required = TRUE AND review_reason_code IS NOT NULL)
    ),
    CONSTRAINT ck_refund_item_financial CHECK (
        financial_status IN ('NOT_STARTED','READY','PROCESSING','UNKNOWN','SUCCEEDED','FAILED','REQUIRES_REVIEW')
    ),
    CONSTRAINT ck_refund_item_financial_shape CHECK (
        financial_status = 'NOT_STARTED'
        OR (requested_amount_vnd > 0 AND approved_quantity = requested_quantity
            AND approved_amount_vnd = requested_amount_vnd)
    ),
    CONSTRAINT ck_refund_item_released_hold CHECK (
        item_type_snapshot <> 'PRODUCT'
        OR fulfillment_status <> 'RELEASED'
        OR shipment_hold_status = 'RELEASED'
    ),
    CONSTRAINT ck_refund_item_approval_pair CHECK (
        (approved_quantity IS NULL AND approved_amount_vnd IS NULL)
        OR (approved_quantity = requested_quantity AND approved_amount_vnd = requested_amount_vnd)
    ),
    CONSTRAINT fk_refund_item_request_order FOREIGN KEY (refund_request_id, order_id)
        REFERENCES refund_requests (id, order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_item_order_item FOREIGN KEY (order_item_id, order_id)
        REFERENCES order_items (id, order_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refund_unit_allocations (
    order_item_id INT NOT NULL,
    unit_position INT NOT NULL,
    refund_item_id INT NULL,
    allocation_status VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'FREE',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC instant',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC instant',
    PRIMARY KEY (order_item_id, unit_position),
    KEY ix_refund_unit_item_status (refund_item_id, allocation_status),
    CONSTRAINT ck_refund_unit_position CHECK (unit_position >= 0),
    CONSTRAINT ck_refund_unit_state CHECK (
        (allocation_status = 'FREE' AND refund_item_id IS NULL)
        OR (allocation_status IN ('RESERVED','REFUNDED') AND refund_item_id IS NOT NULL)
    ),
    CONSTRAINT ck_refund_unit_status CHECK (allocation_status IN ('FREE','RESERVED','REFUNDED')),
    CONSTRAINT fk_refund_unit_order_item FOREIGN KEY (order_item_id)
        REFERENCES order_items (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_unit_refund_item_route FOREIGN KEY (refund_item_id, order_item_id)
        REFERENCES refund_items (id, order_item_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refund_provider_attempts (
    id INT NOT NULL AUTO_INCREMENT,
    refund_item_id INT NOT NULL,
    refund_request_id INT NOT NULL,
    attempt_number INT NOT NULL,
    provider VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    provider_refund_reference VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    amount_vnd BIGINT NOT NULL,
    status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC instant',
    submitted_at DATETIME(6) NULL COMMENT 'UTC instant',
    completed_at DATETIME(6) NULL COMMENT 'UTC instant',
    provider_response_code VARCHAR(32) NULL,
    provider_message VARCHAR(255) NULL,
    response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_provider_attempt_number UNIQUE (refund_item_id, attempt_number),
    CONSTRAINT uk_refund_provider_attempt_route UNIQUE (id, refund_item_id, refund_request_id),
    CONSTRAINT uk_refund_provider_idempotency UNIQUE (provider, idempotency_key),
    CONSTRAINT uk_refund_provider_reference UNIQUE (provider, provider_refund_reference),
    KEY ix_refund_provider_status_created (status, created_at),
    CONSTRAINT ck_refund_provider_attempt_values CHECK (attempt_number > 0 AND amount_vnd > 0),
    CONSTRAINT ck_refund_provider_status CHECK (status IN ('CREATED','SUBMITTED','UNKNOWN','SUCCEEDED','FAILED','REQUIRES_REVIEW')),
    CONSTRAINT fk_refund_provider_attempt_item_request FOREIGN KEY (refund_item_id, refund_request_id)
        REFERENCES refund_items (id, refund_request_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refund_events (
    id INT NOT NULL AUTO_INCREMENT,
    refund_request_id INT NOT NULL,
    refund_item_id INT NOT NULL,
    provider_attempt_id INT NULL,
    event_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    event_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_type VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    event_type VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    actor_user_id INT NULL,
    occurred_at DATETIME(6) NOT NULL COMMENT 'UTC instant',
    from_state VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    to_state VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    reason_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_event_identity UNIQUE (refund_request_id, refund_item_id, operation_type, event_key),
    KEY ix_refund_event_request_time (refund_request_id, occurred_at),
    KEY ix_refund_event_attempt_time (provider_attempt_id, occurred_at),
    CONSTRAINT ck_refund_event_type CHECK (event_type IN (
        'REQUESTED','APPROVED','REJECTED','HOLD_ACQUIRED','HOLD_RELEASED','FULFILLMENT_ADJUSTED',
        'PROVIDER_SUBMITTED','PROVIDER_UNKNOWN','PROVIDER_SUCCEEDED','PROVIDER_FAILED','REQUIRES_REVIEW','RESOLVED'
    )),
    CONSTRAINT ck_refund_event_operation CHECK (operation_type IN (
        'ADMIN_APPROVAL','ADMIN_REJECTION','HOLD_RELEASE_AFTER_ADJUSTMENT',
        'FINANCIAL_EXECUTION_START','LOCAL_PROVIDER_TIMEOUT','PROVIDER_OUTCOME'
    )),
    CONSTRAINT fk_refund_event_request FOREIGN KEY (refund_request_id)
        REFERENCES refund_requests (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_event_item_request FOREIGN KEY (refund_item_id, refund_request_id)
        REFERENCES refund_items (id, refund_request_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_event_attempt_route FOREIGN KEY (provider_attempt_id, refund_item_id, refund_request_id)
        REFERENCES refund_provider_attempts (id, refund_item_id, refund_request_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_event_actor FOREIGN KEY (actor_user_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### DDL limitations and required application checks

- MySQL has no general exclusion constraint for overlapping ranges. The per-unit primary key is the direct uniqueness guard. Under the locked OrderItem row, lazily create/lock unit slot rows, verify their index is less than the immutable OrderItem quantity, then transition FREE to RESERVED. The composite key prevents concurrent duplicate unit rows. Never check overlap from an unlocked snapshot.
- A unique unit slot does not itself cap total VND across different slots. While holding the OrderItem row, calculate each newly reserved unit's amount with the existing helper and ensure the sum for RESERVED/REFUNDED slots is no greater than the persisted `net_amount_vnd`. Store the selected amount snapshot on `refund_items`; verify it equals the sum of selected units. The slot states are authoritative for unit/amount reservations.
- `refund_items.requested_amount_vnd > 0` deliberately limits these tables to financial refund obligations. Current OrderLineSnapshot allows zero-priced Products, so the policy layer may calculate zero; a valid physical cancellation/HOLD for zero-net or unsettled goods must use a separately approved non-financial cancellation workflow, not be rejected solely because it has no provider refund amount. That workflow and persistence are unresolved and are not modeled by this DDL.
- The SQL cannot verify `unit_position < order_items.quantity` using a CHECK (cross-table checks are not supported); validate it under the locked parent OrderItem and use source snapshots. Likewise it cannot itself validate that the refund item snapshot exactly matches OrderItem monetary snapshots or that requested/approved amount equals the sum of selected unit allocations; copy and compare under lock at creation/approval.
- `ck_refund_request_fingerprint` checks only 64-character length; it does not validate hexadecimal SHA-256 content. D code must validate the digest format and canonical request content.
- `refund_events.event_fingerprint` is the required digest of the canonical transition command (including event type/scope); `evidence_sha256` remains a separate optional digest of external evidence. The state-machine `EventIdentity.payloadSha256` maps to `event_fingerprint`, not `evidence_sha256`.
- `refund_events.operation_type` stores the typed operation identity separately from `event_type`, which records the resulting audit event. The candidate unique identity is `(refund_request_id, refund_item_id, operation_type, event_key)`; `event_key` is the stable local event ID. On duplicate identity, lock/read and compare the full canonical `event_fingerprint`; a same-key changed payload is a conflict. The fingerprint is versioned and includes the operation, request/item scope, provider-attempt scope where applicable, and canonical payload. Different operations or obligations may reuse the same raw stable ID without colliding.
- CHECK constraints validate row-local current-state shapes, not legal transition history. Request, fulfillment, financial, slot, and provider-attempt transitions need locked application logic and append-only events. Append-only `refund_events` is an application/database privilege convention in this draft; no trigger prevents UPDATE/DELETE.
- The row-local financial shape check requires approval snapshots before any financial status other than `NOT_STARTED`; it cannot compare the parent `refund_requests.decision_status`, so the transaction must also lock and validate the request/item pair. The released-HOLD check prevents a locally `RELEASED` Product fulfillment state from claiming that this request still owns an active HOLD.
- Composite `refund_events` FKs enforce that its required item belongs to its request and that a non-NULL provider attempt belongs to that same item/request. The nullable attempt reference intentionally bypasses that composite FK for non-provider events.
- The `fk_refund_request_settled_order` makes a financial Refund request impossible without an immutable settlement for that Order. For an unsettled Order, use cancellation/adjustment behavior, not these financial refund tables.
- `shipment_hold_reference` is an immutable historical identifier for E's hold acquisition, not an active-hold flag. `shipment_hold_status` is only D's transactionally updated projection (`ACTIVE`, `RELEASED`, or `REQUIRES_REVIEW`); E's hold-ownership rows are authoritative. `fulfillment_status` is a separate physical-plan axis. An `ADJUSTED` item can retain an ACTIVE hold until E confirms release; RELEASED means no active hold is asserted. The reference remains after release.
- Product `shipment_reference` and `shipment_hold_reference` are bounded opaque references returned by E. They are not a duplicate Shipment table or FK because E's actual identifier/schema is absent. E must confirm whether stable references can be stored this way; if E is in the same schema, a reviewed FK may be preferable.
- The proposed extra candidate keys on `orders` and `order_items` exist only to support route-safe composite FKs. Confirm whether migration V9 needs them; do not duplicate equivalent existing keys.
- If the current V8 entity model does not expose `payment_settlements.order_id` as a referenced candidate key under Hibernate schema validation, add/verify a unique candidate `(order_id)` (already present) and test the FK under MySQL 8 before migration approval.
- V8 currently uses `INT` settlement/order/payment IDs, so new FK columns remain signed `INT`. `BIGINT` is used for whole-VND monetary values. `DATETIME(6)` maps to the existing UTC `Instant` converter contract.
- The SQL is a review draft. Confirm CHECK expression support, multi-FK route mapping, column lengths, and Hibernate naming/validation against the actual MySQL 8.0 server before writing a migration. Do not run it in this phase.

### Provider attempt concurrency limitation

The proposed unique `(refund_item_id, attempt_number)`, `(provider, idempotency_key)`, and `(provider, provider_refund_reference)` constraints prevent duplicate local identities, but they do **not** enforce one active attempt per refund item. There is no portable partial unique index here. Before creating/submitting an attempt, lock the `refund_items` row, require `financial_status=READY` (or a separately approved definitive-failure retry state), transition it to PROCESSING, and insert the attempt in one local transaction. Commit before the provider call. While an attempt is PROCESSING or UNKNOWN, no second attempt may be created; query/reconcile the same attempt first. Local idempotency keys do not prove that VNPay de-duplicates requests. Provider-side idempotency/query guarantees remain unverified; without them, an ambiguous timeout may require manual review and permanently block automatic re-execution.

## Table ownership and mutability

| Table | Owner | Immutable after insert | Mutable fields |
|---|---|---|---|
| `refund_requests` | D | Owner/order/key/fingerprint/requested time/reason | Admin decision fields and updated time; every transition also appends an event |
| `refund_items` | D | OrderItem type/quantity/money snapshots, requested allocation/amount, historical E references | Hold projection, fulfillment/financial state; MVP approval must approve the complete selection |
| `refund_unit_allocations` | D financial allocation ledger; no inventory ownership | Unit identity; REFUNDED is terminal | FREE to RESERVED; RESERVED to FREE only on safe financial release; RESERVED to REFUNDED on verified provider success |
| `refund_provider_attempts` | D | Obligation, attempt number, provider, idempotency key, amount, creation time | Provider reference and attempt outcome/timestamps/evidence digest; never rewrite successful attempt |
| `refund_events` | D | All columns; append only | None. Corrections append a new event |
| Shipment/HOLD/packing/inventory tables | E | Defined by E | Defined by E. D must not duplicate them |

`refund_unit_allocations` is the **financial-unit reservation ledger**, not a Shipment HOLD ledger. Its states mean: **FREE** = no active or successful financial refund claim; **RESERVED** = one request owns the unit and its net VND allocation is reserved; **REFUNDED** = verified provider success permanently consumed the financial allocation. For Product, initialize/acquire one logical slot per requested whole unit. A Course item (quantity one) may use the same slot table but has no Shipment HOLD.

A request rejection may return RESERVED to FREE only after the provider obligation is absent/definitively cancelled and, for Product, E separately confirms the physical HOLD release. Releasing a financial reservation never itself releases an E Shipment HOLD; E hold release never frees D's financial allocation without a separate locked D transition. An UNKNOWN provider outcome retains RESERVED. If physical fulfillment was adjusted and a provider attempt definitively fails, keep the slot RESERVED/review-required; do not silently free it or make the unit refundable again. Reuse requires an explicitly approved compensating physical operation and financial decision.

If physical fulfillment was already durably adjusted and a provider attempt later definitively fails, do **not** automatically free the slots: the physical goods may no longer be in the shipment. Keep them reserved/review-required until an explicit Admin/manual resolution defines the physical and financial disposition. This prevents a new request from claiming units already removed from the packing plan.

## State model: separate decision, physical, and financial axes

### Request decision

| From | To | Authority / conditions |
|---|---|---|
| new | `REQUESTED` | Customer request, owner verified, committed settlement exists, amount/range snapshot valid; Product additionally requires atomic E HOLD in the same transaction |
| `REQUESTED` | `APPROVED` | Admin approves the complete requested unit selection in MVP; no arbitrary smaller quantity/amount |
| `REQUESTED` | `REJECTED` | Admin decision; release E HOLD and unit slots atomically or retain review state if E release cannot be proven |
| `APPROVED` / `REJECTED` | opposite decision | Illegal; corrections require append-only reviewed resolution/audit, not a silent rewrite |

### Fulfillment (per `refund_items`)

| From | To | Meaning |
|---|---|---|
| Product request creation | fulfillment `PENDING`, hold `ACTIVE` | E confirms Shipment hold and quantity reservation atomically with D request. Hold reference is historical; status is a separate projection. |
| fulfillment `PENDING` | `ADJUSTED` | Admin-approved complete selection applied to E packing/fulfillment and inventory state in a REQUIRED transaction; hold may remain ACTIVE until E confirms release. |
| fulfillment `PENDING` | `RELEASED` | Request rejected/cancelled and E confirms no remaining hold owner needs the hold; the separate hold status becomes RELEASED. |
| any uncertain E outcome | `REQUIRES_REVIEW` | No automatic handover, release, or second adjustment until reconciled |
| Course request | fulfillment `NOT_REQUIRED`, hold `NOT_APPLICABLE` | No physical Product Shipment operation |

### Financial provider outcome (per `refund_items` and attempts)

| From | To | Meaning |
|---|---|---|
| New request | `NOT_STARTED` | No provider execution is permitted before Admin approval |
| Approved and (for Product) `ADJUSTED` | `READY` | A committed settlement and approved immutable amount exist |
| `READY` | `PROCESSING` | Provider attempt persisted before network call |
| `PROCESSING` | `SUCCEEDED` | Verified definitive provider success; allocations become permanently REFUNDED |
| `PROCESSING` | `FAILED` | Definitive provider failure only; retry/resolution policy applies, and this does not automatically release physical/unit allocations |
| `PROCESSING` | `UNKNOWN` | Timeout/ambiguous outcome; keep same attempt/reference and all reservations; query/reconcile, never launch a duplicate refund |
| any conflict | `REQUIRES_REVIEW` | Conflicting, malformed, or unrouteable outcome; preserve evidence and prevent new execution |

`PROCESSING`, `SUCCEEDED`, `FAILED`, and `UNKNOWN` are not alternatives for the customer Admin decision. They are provider state. A request can be `APPROVED` while its fulfillment is `ADJUSTED` and financial state is `UNKNOWN`. A customer-facing aggregate status should be derived only after product/API requirements define it.

## Financial and idempotency invariants

1. One request key per `(user_id, idempotency_key)`; same key + same canonical fingerprint returns the canonical request, different fingerprint is a conflict.
2. Initial request is one OrderItem only. Fingerprint includes Order ID, OrderItem ID, sorted logical unit positions/ranges, normalized reason code, and any customer-supplied request fields that change meaning.
3. Request admission locks Order, payment attempts and settlement, then (for Product) Shipment and shipment-item rows, OrderItem, and refund rows in the candidate order below. A request cannot cite client price/amount; calculate from immutable snapshots.
4. Product unit allocation primary key `(order_item_id, unit_position)` protects against duplicate active/successful unit claims. RESERVED/REFUNDED unit amounts are computed from the snapshot helper and sum-capped at `net_amount_vnd` under OrderItem lock.
5. A row in RESERVED state backs a requested/approved, processing, failed-but-unresolved, or unknown obligation. It can return to FREE only when the request is rejected/cancelled before irreversible physical/provider effects and the matching E HOLD is separately confirmed released.
6. SUCCEEDED refund allocations are terminal REFUNDED. Neither a new request nor a retry can reserve/refund them again.
7. A provider attempt is unique by `(refund_item_id, attempt_number)`; provider idempotency key and provider reference are unique within provider. New retry attempts belong to the same approved refund item, never a new request.
8. UNKNOWN outcome retains the same provider attempt/reference/idempotency key and reservations. Resolve by provider query/reconciliation. Do not issue a second independent refund.
9. Amount snapshots in `refund_items` are immutable for requested amounts. MVP Admin approval is all-or-reject for the complete requested unit selection; SQL enforces approved quantity/amount equality when those fields are populated. Partial approval of a smaller amount is OUT OF SCOPE for MVP; a future policy change requires explicit allocation redesign and approval.
10. Missing settlement, mismatched owner/OrderItem route, full amount already refunded/reserved, invalid evidence, or E hold failure rejects/fails closed. A failed transaction must roll back request, unit reservation, audit row, and E hold together.

## Lock order proposal

Use this proposed order for a refund acceptance/approval path, then have E compare it with its actual shipment handover/inventory order:

**Candidate shared order (UNVERIFIED until Member E confirms its actual handover/inventory implementation):**

1. Order row (`orders.id`, pessimistic write).
2. All PaymentAttempt rows for the Order in ascending ID order.
3. Settlement row by Order (locking/current read); verify the immutable settlement. Existing settlement/expiration paths acquire Order -> Attempt(s) -> settlement before later mutable resources.
4. For product refunds, E Shipment aggregates in ascending stable Shipment ID/reference order. This matches the relative Shipment-before-shipment-item/refund allocation order in `REFUND_SHIPMENT_HOLD_CONTRACT.md`.
5. E shipment-item/packing rows in stable key order.
6. D OrderItem rows in ascending ID order.
7. D refund request/item rows in ascending ID order.
8. D unit-allocation rows ordered `(order_item_id, unit_position)`.
9. E inventory reservation/stock rows in ascending Product ID.
10. D provider-attempt row; append D audit event after the associated changes.

Provider network calls occur outside the transaction. `refund_events` append with the corresponding local state update. The proposal previously placed OrderItem/refund locks before Shipment locks, opposite the earlier HOLD contract; this order corrects that inconsistency. It is only a candidate: E must confirm its actual handover, packing, and inventory lock sequence. In particular, if E inventory code takes Inventory then Shipment while refund code takes Shipment then Inventory, that creates a deadlock cycle; E must either align the order or provide a different jointly reviewed sequence. Do not infer E's order. Existing settlement/expiration paths lock Order -> PaymentAttempt(s) -> settlement, then Voucher -> Product inventory -> Course claims; refund code does not lock Voucher/Course resources. Refund operations must not add unnecessary Voucher locks because the original discount is in immutable OrderItem net snapshots.

## Concurrency and failure matrix

| Race/failure | Required outcome and guard |
|---|---|
| Two requests target same Product units | Order row plus Shipment and OrderItem locks serialize candidate selection; unit-slot PK and RESERVED/REFUNDED state reject second allocation. MySQL independent transactions must verify this. |
| Two requests target different units in one Shipment | Both may reserve disjoint slots; Shipment HOLD ownership is per request. Rejection/approval of one cannot clear another's hold. |
| Refund acceptance races carrier handover | E locks the same Shipment aggregate. HOLD commit first blocks handover; handover commit first rejects request. Missing/ambiguous serialization result fails closed. |
| Admin approval races another request | Order/OrderItem locks protect money/unit cap; Shipment and unit slot locks protect physical/quantity allocation. Approval cannot exceed the accepted unit allocation. |
| Admin rejects while another request owns HOLD | Request-specific release; E recomputes remaining HOLD owners under Shipment lock and keeps Shipment held if any remain. |
| Provider success callback replay | Lock Order/refund item/provider attempt; same verified provider event/reference is idempotent; already-SUCCEEDED returns prior state and cannot mutate settlement or duplicate amount. Local uniqueness helps; provider replay identity needs verified provider contract. |
| Provider timeout followed by retry | Mark UNKNOWN and retain reserved unit/amount; query the same attempt/reference. Do not assume the provider enforces idempotency; no new execution until definitive result/review and verified provider retry semantics. |
| Rollback after slot allocation and HOLD acquisition | D request, item, slots, events and E hold all join the same transaction; any failure rolls all back. Unknown commit outcome must be queried by idempotency key before retry. |
| Mixed Course/Product Order | Per-item request locks only that OrderItem. Course uses C evidence and no Shipment HOLD; Product uses E handover/HOLD. Shared Order financial cap remains serialized. |
| Full amount already refunded/reserved | Slot ledger and sum cap reject another allocation. Course quantity one has no remaining free slot after success/active obligation. |
| Settlement missing/inconsistent | FK to `payment_settlements(order_id)` plus Order lock rejects admission. Route/owner/settlement inconsistency fails closed; never create provider attempt. |
| Definitive provider failure after physical adjustment | Keep allocation and fulfillment adjustment; mark financial failure/review. Do not free units or reverse packing automatically. Admin policy must determine safe resolution. |

## Alternatives considered

### Range rows only

One row per range is compact, but MySQL has no exclusion constraint to reject overlapping intervals. Correctness would depend entirely on every writer taking the exact parent OrderItem lock and checking interval overlap. A code path omitting that lock could double-allocate a unit.

### Aggregate quantity counters

Counters are compact but cannot identify which logical units were refunded; retries can double-adjust counters, and remainder allocation needs stable unit ordering. Counters alone do not satisfy 鈥渟ame logical unit cannot be refunded twice.鈥?
### Per-unit slot ledger (recommended)

One row per logical unit position uses a primary key for direct uniqueness and explicit FREE/RESERVED/REFUNDED state. It is straightforward to reconcile and maps exactly to `netAmountForUnits`. Costs are row count and the need to lock the parent OrderItem while lazily creating slots. It models logical units, not serial-numbered physical inventory; E must confirm how those positions map to packing.

## H2 and guarded MySQL test plan

### Isolated H2 persistence tests after implementation

- Hibernate/Flyway mapping validation for each new table, FK route, unique constraint, CHECK, status, and `DATETIME(6)` Instant conversion.
- Same-key same-fingerprint replay and changed-fingerprint conflict; owner isolation.
- Composite owner/order and OrderItem/order FKs reject cross-route records.
- Duplicate `(order_item_id, unit_position)` cannot be allocated twice; FREE release can be reacquired; REFUNDED cannot be reacquired.
- Amount snapshots reconcile to OrderItem net snapshot and deterministic per-unit sums, including 89,999 VND across three units.
- Full refund amount reserved/refunded blocks further allocation; failed transaction leaves no request, item, slot, attempt, or audit event.
- Provider UNKNOWN preserves allocations and the same attempt key/reference; success replay does not duplicate effects.
- Legal request, fulfillment, and provider state transitions plus append-only events.

H2 can verify mappings, FK/unique/CHECK behavior and transaction rollback, but not MySQL gap/row locking or real E handover behavior.

### Guarded MySQL tests (not run in this phase)

Target only `jdbc:mysql://127.0.0.1:3306/drawing_course_it` with the existing explicit `MYSQL_IT_DATABASE_ACK=drawing_course_it` pre-context guard. Use independent transactions/barriers and bounded lock waits; never use `drawing_course_dev` or Flyway clean. Verify:

- two concurrent allocations of the same unit: exactly one active owner;
- concurrent requests for distinct unit positions: both can succeed and shared Shipment HOLD remains until all owners resolve;
- remaining-amount cap at exact boundary under concurrency;
- refund acceptance versus real E handover serialization;
- same-key replay, request/decision/Provider callback idempotency;
- rollback after D slot allocation and E HOLD/packing mutation;
- UNKNOWN provider result retains one obligation and cannot dispatch a second refund;
- candidate-key composite FKs and V8 settlement FK behavior under MySQL validation.

The Shipment/HOLD and packing/inventory tests cannot run until E persistence and the actual handover operation are integrated. A D-only test adapter proves only D transaction wiring, not production E safety.

## Unresolved decisions

1. Course seven-day window origin and inclusive/exclusive boundary remain unresolved; the pure policy requires callers to supply both.
2. **OUT OF SCOPE for MVP:** Admin approval is all-or-reject for the complete requested unit selection. Arbitrary partial monetary approval is not supported; any future policy change requires a separately reviewed allocation rule and schema update.
3. Can one refund request include multiple OrderItems? The proposed unique `refund_items.refund_request_id` fixes the initial design at one item/request, matching per-item request policy. Multi-item aggregation requires approval and partial-success semantics.
4. The state-machine foundation retains allocations and requires review after a definitive provider failure following physical adjustment. Any compensating fulfillment operation remains a future Admin/E-owned policy, not an automatic retry.
5. What exact Admin roles/reason codes and audit retention apply?
6. What stable provider refund identity/query/idempotency support does VNPay provide? Unknown outcome must not permit an independent retry.
7. Does full physical Shipment cancellation refund shipping? Partial Product refunds do not automatically refund it; full Shipment cancellation remains unresolved.
8. What is E's actual shipment/item/hold/packing/inventory model and lock order? The proposed D schema has no E table or E FK.
9. How are logical OrderItem unit positions mapped to packed physical units where shipment lines are split or repacked?
10. Is zero-net OrderItem eligible for a non-financial cancellation only? Proposed financial request requires a positive amount.
11. Can every Product hold and approved fulfillment adjustment join the same local transaction manager, or is a durable saga/outbox required?

### Shipping charge extension if later approved

Shipping is not a Product unit and must never be inserted into `refund_unit_allocations`. Partial Product refunds do not refund shipping. If a future decision approves refunding a full Order shipping charge, represent it as a separate charge component/adjustment row referencing the immutable Order shipping charge snapshot, with a unique key such as `(order_id, charge_kind='ORDER_SHIPPING', charge_reference)` and a positive bounded VND amount. The unique original-charge reference plus locked Order and remaining-charge check would permit at most one refund of that charge. The current schema has no per-Shipment shipping-fee allocation, so do not invent proportional fees for multiple Shipments; approve the business allocation and add a reviewed table/column before implementation.

### Zero-net or unsettled physical cancellation

These proposed tables represent **financial refunds only** and require a committed settlement and positive amount. A zero-net Product can still need a valid pre-handover physical cancellation/HOLD/packing adjustment, and an unsettled Order may need cancellation. If the team wants those flows, model them as a separately named fulfillment/order-adjustment obligation with its own idempotency/audit and E integration. Do not create a zero-amount provider attempt or reject a legitimate physical adjustment merely because the financial refund amount is zero. This adjacent non-financial workflow remains unresolved and is outside the current DDL.

## Implementation prerequisites

1. Member E confirms actual Shipment/HOLD/handover and inventory tables, operation lock order, quantity/unit mapping, idempotency key, and same-datasource transaction participation.
2. Member C confirms Course progress/assignment evidence and seven-day time origin/boundary.
3. Finance/Admin approves provider retry, amount granularity, role/reason, and unknown-outcome resolution policy.
4. Review the proposed unit-slot schema and candidate-key additions with the owners of V3/V8. Create no migration until these reviews are complete.
5. Implement D-owned entities/services and isolated H2 tests; then guarded MySQL constraint/concurrency tests on `drawing_course_it`; then real E adapter tests before enabling any endpoint.

## Current activation boundary

This proposal does not register or enable a Refund workflow. Product request acceptance cannot be implemented safely until E HOLD is atomic with carrier handover. Financial execution cannot be enabled until a committed settlement exists, provider idempotency/query behavior is verified, and unknown outcomes are reconciled. No Checkout, Settlement, Expiration, IPN, or Refund activation is part of this design.

## Pure state-machine foundation (D-Refund-3B1)

`RefundStateMachine` is a database-free immutable transition model. It separates the Admin decision (`REQUESTED/APPROVED/REJECTED`), physical fulfillment (`NOT_REQUIRED/PENDING/ADJUSTED/RELEASED/REQUIRES_REVIEW`), Shipment HOLD projection (`NOT_APPLICABLE/ACTIVE/RELEASED/REQUIRES_REVIEW`), and financial refund status. COURSE requests carry no Shipment state. PRODUCT request creation accepts only explicit confirmed HOLD evidence; approval requires typed confirmed fulfillment-adjustment evidence, and rejection requires typed confirmed release evidence. After approved physical adjustment, the HOLD can be confirmed released while fulfillment remains `ADJUSTED` and financial status remains unchanged. State construction includes a positive request/item `RefundScope` so event identities cannot be replayed across obligations.

Approval is **all-or-reject for the full requested unit selection; arbitrary partial monetary approval is OUT OF SCOPE for MVP**. A zero-net request can be physically approved but remains `NOT_STARTED` financially; this pure model does not implement the separate non-financial cancellation persistence needed for an unsettled Order.

Financial execution becomes `READY` only after Admin approval and required item-specific evidence, with a committed settlement and positive amount. Only explicitly supplied provider evidence marked verified and matching amount/currency can produce success. `UNKNOWN` sets and retains `reviewRequired=true`, blocks another execution, and can reach a terminal result only through evidence typed `RECONCILIATION`; an initial result cannot resolve it. A successful reconciliation may clear this marker for the provider-uncertainty cause. Replaying the same typed event identity and payload digest is idempotent; reusing the same operation/scope/stable ID with a different digest is a conflict. Different operation types and request/item scopes are distinct identities. A definitive failure after Product fulfillment adjustment remains `FAILED` with `reviewRequired=true`; the physical adjustment and HOLD facts are not reversed.

The model stores applied event identities in memory solely to make the pure transition behavior testable; restart-safe replay requires persisted `refund_events`. Persist operation type, request/item scope, stable event key, canonical fingerprint, and applicable provider-attempt reference. `evidence_sha256` remains optional evidence metadata and is not the replay fingerprint. `reviewRequired` is independent and applies across workflow axes: the proposal has `review_required` plus `review_reason_code`; do not encode a Shipment/HOLD issue solely as financial `REQUIRES_REVIEW`. Provider-attempt status preserves the underlying provider outcome. No general review-resolution operation is added: only provider UNKNOWN can be cleared by typed verified reconciliation; Shipment/HOLD or adjusted-fulfillment review remains unresolved until an authorized Admin/E evidence contract is approved.

These functions accept typed evidence claims but do not authenticate evidence, verify VNPay signatures, prove Shipment HOLD ownership, reserve/refund allocations, or make concurrency guarantees. Those remain caller, provider, persistence, and Member E integration responsibilities.

### D-Refund-3B2.1 correctness amendment

The state machine represents `reviewRequired` as the derived view of an immutable set of typed review causes. UNKNOWN adds `PROVIDER_OUTCOME_UNKNOWN`; a verified reconciliation removes only that cause. A definitive provider failure after Product fulfillment adjustment adds `PHYSICAL_ADJUSTMENT_WITH_PROVIDER_FAILURE`. No generic clear-review transition exists. The proposal's single `review_required`/`review_reason_code` pair can represent one open cause but not multiple simultaneous causes; this is a schema blocker unless MVP explicitly serializes review causes or a separately reviewed child-case representation is added.

`EventIdentity` now contains a typed `OperationType`, `RefundScope(requestId,itemId)`, bounded stable event ID, and canonical SHA-256 payload fingerprint. The pure replay map keys by `(operation, scope, stableEventId)` and compares the fingerprint. Same tuple/same fingerprint is idempotent; same tuple/changed fingerprint conflicts. Reusing a raw stable ID under another operation or request/item is distinct. `refund_events` therefore requires `operation_type` separately from resulting `event_type`, the candidate unique key `(refund_request_id, refund_item_id, operation_type, event_key)`, and a locked application read/compare of `event_fingerprint`. The fingerprint must include versioned provider-attempt scope and canonical facts when applicable.

`markUnknown()` and verified `ProviderOutcome.UNKNOWN` both set financial UNKNOWN and open the provider-uncertainty review cause. Only caller-verified `ProviderEvidenceKind.RECONCILIATION` can resolve UNKNOWN; this pure type is a declaration, not signature verification. An initial provider result cannot resolve it. Reconciliation success clears only provider uncertainty; adjusted fulfillment/provider failure keeps its separate review cause. Review resolution for Shipment/HOLD uncertainty remains undefined pending authoritative E evidence and Admin authorization. In-memory identities are only a unit-test aid and do not provide restart-safe replay.
