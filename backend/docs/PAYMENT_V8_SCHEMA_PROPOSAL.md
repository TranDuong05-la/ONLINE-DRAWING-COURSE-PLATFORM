# Payment V8 Schema Proposal

## Status and inspected baseline

Approved design decision: settlements are immutable. Exactly one successful settlement may exist per Order; it is inserted only in the transaction that confirms the Order and finalizes local reservations. A committed settlement is never updated or deleted. Refunds, chargebacks, and financial adjustments belong in separate records in a later phase. Duplicate successful callbacks return the existing settlement and do not repeat business effects. No separate PROCESSING settlement state exists.

The reviewed design is implemented as `V8__payment_settlement_reconciliation.sql`, with matching JPA persistence and guarded MySQL schema tests. The user reports that the guarded MySQL suite passed 23/23 and the settlement/expiration concurrency suite passed 10/10 against `drawing_course_it`. Those concurrency tests use test-only transaction-bound Course/Inventory adapters; they do not verify future production B/C adapters. No database was accessed during the D-Payment-2F checkpoint review.

The inspected worktree is on branch feature/member-d-commerce at HEAD bc4ef5a04e7f0af7ccf7d2717b2f9efe7b48125c. This is the actual HEAD used. The existing untracked backend/docs/PAYMENT_SETTLEMENT_READINESS.md was preserved and used as the source review.

V1 defines users.id INT; V3 defines orders.id INT; V5 defines payment_attempts.id INT, payment_events.id INT, and payment_event_conflicts.id INT. These signed INT identities are compatible for the proposed references. V5 uses binary collations for technical payment identifiers, immutable event evidence, and RESTRICT foreign keys. Its event and conflict uniqueness must remain authoritative; V8 must not create another event or conflict log.

## Design decisions

1. payment_settlements is an immutable ledger of successful, on-time settlements only. A row means the Order confirmation transaction committed. Exactly one row may exist per Order, PaymentAttempt, and PaymentEvent. No PROCESSING state is needed because the row, Order transition, PaymentAttempt transition, and local reservation finalizations commit in the same transaction. Financial corrections are separate records, never settlement edits.
2. An Order, PaymentAttempt, and PaymentEvent can each be used by at most one settlement. A settlement row references all three and verifies their route consistency under lock. Optional composite foreign keys make mismatched Order/attempt/event combinations impossible at the database boundary.
3. Provider payment time and local settlement time are separate UTC DATETIME(6) values. Do not constrain their relative ordering in SQL: provider and application clocks can differ, and a provider timestamp slightly later than local settlement can still be valid for the Order deadline policy. The application enforces provider payment time and IPN receipt time separately, both strictly earlier than the persisted Order deadline. Equality with the deadline is expired.
4. payment_reconciliation_cases is a bounded review queue, not a second event log. Known cases link to the immutable V5 event and, for conflicts, the existing V5 conflict row. Unknown merchant references have no Order/attempt/event FK and retain only bounded routing/status/amount/time fields plus a digest and deterministic fingerprint. For known amount-mismatch cases, preserve the exact signed minor-unit amount because V5 stores whole-VND amounts and cannot reconstruct fractional VND. A verified known callback with no provider transaction number can be stored as a review case without a fabricated PaymentEvent or transaction reference.
5. Reconciliation-case uniqueness is based on provider, event identity, classification, and normalized evidence fingerprint. Re-delivery of the same facts returns the existing case; a different signed fact set for the same event identity remains separately reviewable. For known callbacks, V5 remains the source of event facts except exact minor units for amount mismatch because V5 stores whole VND only. Currency mismatch classification requires a signed provider currency field; the current callback verifier has no typed currency accessor and the reconciler currently fixes event currency to VND.
6. No fulfillment outbox is included by default. The Course and inventory ports describe local database mutations and can participate in one local transaction if B/C/E implement them against the same datasource and transaction manager. Those production adapters do not currently exist, so this is a required integration proof. If a required effect calls another service or cannot join the transaction, add a transactional outbox for that external follow-up before enabling it.
7. Keep existing Order status/payment-status and PaymentAttempt status/review columns. Do not add a duplicate payment status or settlement state to those tables.

## Proposed V8 SQL (documentation only)

Target MySQL: 8.0.16 or later, InnoDB. The version floor is required for enforced CHECK constraints. Names and column types below follow V1-V7. The migration must be separately reviewed and must not be run as part of this design task.

The current verifier requires vnp_TransactionNo, vnp_TxnRef, vnp_Amount, vnp_ResponseCode, vnp_TransactionStatus, vnp_TmnCode, vnp_BankCode, and vnp_OrderInfo; it permits vnp_PayDate to be absent. It signature-verifies other single-valued vnp_* parameters, but the typed callback has no currency accessor. The current reconciler sets event identity to vnp_TxnRef + ':' + vnp_TransactionNo. Source code therefore confirms that these values are present and signed, but does not establish a provider guarantee that TransactionNo is a stable, globally unique event ID. Treat the current composition as the application's event key, not as a verified provider event-ID contract. Thus the missing-transaction-number case below is forward-compatible storage only; the current verifier rejects it before reconciliation. Supporting it requires a separately reviewed verifier/service change and must not invent a VNPay transaction ID.

Event identity derivation for that future case is deterministic: when a nonblank signed vnp_TransactionNo exists and the composed TxnRef + ':' + TransactionNo fits the V5 255-character event_reference and transaction_reference limits, use that composition. If it is absent or unusable/too long, compute SHA-256 over UTF-8 VnPaySignature.canonical(all signed vnp_* parameters, excluding vnp_SecureHash and vnp_SecureHashType), then use vnp_TxnRef + ':payload:' + lowercase hex digest as event_reference. This fallback is at most 173 characters because TxnRef is capped at 100 characters; it is a local payload-derived identity, not a provider event ID. Exact redelivery of identical signed fields maps to the same identity; changed signed fields map to a separate review identity because no stable provider transaction ID is available. Such a callback must never settle automatically.

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

## DDL notes and JPA alignment

- INT (signed) matches V1/V3/V5 identifiers; no BIGINT/INT FK mismatch is introduced. BIGINT is used only for VNPay minor-unit amount metadata.
- The composite unique keys are deliberate candidate keys for the composite FKs. They do not alter V1-V7 data or business values. Before migration approval, confirm their names are unused. Primary IDs make duplicate composite tuples impossible.
- Settlement uniqueness prevents a second Order settlement, attempt reuse, or event reuse. The composite FKs prove event-attempt-Order routing agreement. The application must additionally prove provider is VNPay, both persisted rows refer to the same owner, event outcome is SUCCEEDED, amount/currency match, and provider-paid time plus event receipt time are each strictly before the exclusive deadline.
- Define `evidence_fingerprint` as `v1:` plus lowercase SHA-256 hex. For a case linked to a V5 event, use that event's immutable fingerprint; for a case linked to a V5 conflict, use its `conflicting_fingerprint`; for unknown/unusable-reference cases without a V5 row, hash the verifier's canonical signed parameter string. Do not include IPN receipt time, so redelivery of the same signed evidence resolves to the same case. The separate `payload_sha256` stores the canonical signed-parameter digest only for cases with no V5 event; linked cases already have that digest in V5.
- The former ck_payment_settlement_time was removed. Ordering provider time against local settlement time is not a safe cross-clock invariant and is not required by the business deadline rule. Keep the two strict comparisons against Order.payment_expires_at in the locked application transaction.
- Settlement insertion is the idempotency claim. Under the Order lock, a repeat using the same settled event returns the existing settlement without repeating side effects. A different event for an already-settled Order leaves the original ledger and Order untouched and creates a SETTLEMENT_CONFLICT case linked to the new V5 event. If the event conflicts with an existing event identity, preserve V5 payment_event_conflicts and link a CONFLICTING_CALLBACK case. Check the ledger under the Order lock before writes; do not catch a unique-key error and continue in the same failed transaction. Keep unique constraints as the final race guard. Do not commit a separate PROCESSING settlement row.
- Case identity is stable across delivery retries. For a known event, the case references V5 and does not copy its callback facts, except exact minor units for amount-mismatch cases because V5 stores only whole VND. For an unknown reference or unusable transaction reference, there is no suitable V5 event row to reference; only the bounded fields above are stored. Currency mismatch relies on the linked event's payload digest and classification rather than copying the raw currency. Never store SecureHash, secret keys, raw query strings, signed URLs, or full callback maps.
- The foreign key column order and types match V5. Optional composite FKs are skipped by MySQL when a nullable component is NULL, which allows an unknown-reference case but does not relax its explicit route CHECK.
- The route CHECK uses explicit IS NULL/IS NOT NULL terms for nullable route and payload fields. MySQL accepts UNKNOWN results from CHECK expressions, so any future predicate involving nullable values must be written to evaluate explicitly TRUE or FALSE for each accepted branch.
- Current verifier behavior is source-verified: vnp_TransactionNo is required; vnp_PayDate is optional; TmnCode, Amount, BankCode, OrderInfo, ResponseCode, TransactionStatus, and TxnRef are also required. The callback wrapper exposes those strings and the full immutable signed parameter map. The reconciler uses TxnRef plus TransactionNo as its current event_reference. A missing transaction number is therefore rejected today and cannot be persisted by current code. The proposed UNUSABLE_TRANSACTION_REFERENCE shape is for a future verifier-approved case only; it also covers a reference too long for V5's 255-character event/transaction columns and does not fabricate a provider ID.
- For a future callback accepted without a usable TransactionNo, deterministic fallback event_reference uses TxnRef plus SHA-256 of the verifier's canonical signed parameter string. This makes exact retries idempotent. Since different signed payloads produce different fallback identities, they cannot be proven to be the same provider event; route each to manual review and never settle automatically. For unknown-merchant and unusable-reference cases, provider_outcome is normalized to SUCCEEDED, FAILED, or UNKNOWN rather than copying raw response/status strings. The current verifier accepts any nonblank response/status string, so storing them in CHAR(2) columns would reject validly signed but ambiguous callbacks. Merchant reference, numeric amount, receive time, timestamp-state classification, payload digest, and evidence fingerprint remain required. A missing or malformed PayDate is represented by nullable normalized provider_paid_at, provider_timestamp_state, and digest, never the raw callback value.
- V5 stores successful PaymentEvent rows with a nonnull transaction_reference at the Java domain layer, although its SQL column is nullable. A known success lacking a usable TransactionNo must use the UNUSABLE_TRANSACTION_REFERENCE case path and must not attempt a V5 PaymentEvent or settlement insert. For an unknown merchant, use UNKNOWN_MERCHANT_REFERENCE whether or not TransactionNo is present.
- VnPayCallbackVerifier signs/verifies all single-valued vnp_* parameters present, excluding SecureHash and SecureHashType from the canonical string. It does not currently require or expose vnp_CurrCode through a typed accessor; the reconciler currently treats currency as VND. Only create a CURRENCY_MISMATCH case if a future reviewed application path validates a signed provider currency field. Do not infer a currency mismatch from an absent field.
- V8 JPA entities should use Integer IDs, explicit VARCHAR enum mappings, UTC Instant converters to DATETIME(6), and named unique/FK/index constraints matching the DDL. Hibernate ddl-auto=validate should validate columns; the MySQL integration test must separately assert named keys, CHECKs, and referential behavior.
- H2 tests use the existing isolated in-memory test profile and Hibernate-managed schema; they do not execute this MySQL migration. H2 can test entity behavior, idempotent service logic, and transaction rollback, but cannot establish MySQL collation/CHECK/composite-FK behavior. Do not claim this DDL is cross-database because it uses MySQL-specific InnoDB and charset clauses.

## Transaction and lock ordering

Use a single REQUIRED transaction and the same MySQL datasource/transaction manager for settlement, Order transition, voucher consumption, Course claim finalization, inventory reservation confirmation, settlement ledger insertion, and reconciliation-case updates.

1. Verify/normalize the signature before database side effects. Do not call VNPay or another remote service while holding locks.
2. Checkout reservation order: lock/claim the owner-scoped Checkout idempotency key; lock the Cart; if a voucher is present, lock Voucher definition then per-user usage; insert the immutable Order; reserve Product inventory rows in ascending Product ID; claim Course rows in ascending Course ID; persist the voucher claim while holding its definition/usage locks; complete Checkout idempotency. Current Checkout source takes the voucher lock before Order insertion, then Product, Course, and voucher claim. Keep that order consistent in implementations.
3. Payment settlement order: lock Order; lock its PaymentAttempt rows by ascending ID; lock Voucher definition, per-user usage, then VoucherClaim; lock Product/inventory rows by ascending Product ID; lock Course purchase-claim rows by ascending Course ID; insert the settlement row and update audit/review state; commit all transitions together. Voucher consumption must see the Order transitioned to CONFIRMED/SUCCEEDED inside this same transaction.
4. Order expiration/cancellation order: lock Order; lock related PaymentAttempts by ascending ID; recheck terminal eligibility and the exact exclusive deadline; lock Voucher definition, usage, and claim; lock Product rows by ascending Product ID; lock Course claim rows by ascending Course ID; transition Order to EXPIRED/CANCELLED and release all claims in the same transaction. Never expire or release while an attempt is UNKNOWN or requires manual reconciliation unless an explicit policy says how to handle it.
5. Voucher methods already require a caller transaction and acquire Order -> Voucher definition -> usage -> claim. When the caller already owns the Order lock, their internal Order lock is reentrant for that transaction. Course/inventory adapters must document and match the Product-then-Course order used by Checkout. All lists are sorted before taking row locks.

The settlement transaction cannot make an external side effect atomic. If any B/C/E operation is remote, use an outbox with a unique (order_id, action_type), minimal reference payload, PENDING/PROCESSING/COMPLETED/FAILED state, bounded retry metadata, and idempotent consumer. Create it in the settlement transaction, then perform remote work after commit. Do not add this table by default while all settlement finalization is local database work.

## Dependencies and boundaries

- Member B must provide the trusted local Course catalog implementation, including purchasability and current name/price reads used by Checkout. No production Course adapter was found.
- Member C must provide owner-scoped ownership/pending-purchase checks and atomic, Order-idempotent Course claim/reserve, confirm, and release operations backed by its canonical ownership data. No production implementation or claim persistence was found.
- Member E must provide trusted Product lookup, stock reservation/confirmation/release, and shipping quote/snapshot. No production Product, inventory, reservation, or shipping implementation was found.
- All three must confirm same-database participation, REQUIRED transaction joining, row-lock behavior, uniqueness keys, and retry semantics. An interface alone does not prove atomicity.
- V8 does not add refunds. Late success and mismatches stay open reconciliation cases; no automatic refund or fulfillment occurs.
- V8 does not add a fulfillment outbox by default. Add one only if a required post-settlement action cannot be completed as a local participant in the settlement transaction.

## Verification plan before approving an actual V8 migration

### Isolated H2 suite

After entity/service implementation, test:

- settlement insertion and replay for the same Order/attempt/event;
- unique conflict for a second settlement candidate;
- route mismatch rejection;
- success only when provider paid time and receipt time are each strictly before the Order deadline;
- exactly-at-deadline rejection;
- transaction rollback leaves Order, attempt, voucher counters/claim, Course/inventory claims, settlement row, and reconciliation case unchanged;
- repeated release/consume/confirm does not double-adjust claims.

These validate application semantics and entity mappings only.

### Guarded MySQL integration suite

Extend only the existing guarded mysql-it suite, targeting exactly jdbc:mysql://127.0.0.1:3306/drawing_course_it with MYSQL_IT_DATABASE_ACK=drawing_course_it. Verify Flyway V1-V8 success, Hibernate validation, DATETIME(6)/column nullability, named unique keys and composite foreign keys, CHECK enforcement, unknown-case insertion without Order/attempt FKs, case-sensitive event identity, duplicate case idempotency, settlement rollback, and concurrent same-Order settlement with independent transactions. Confirm V1-V7 records remain successful and do not run clean/drop/recreate. Never run this against drawing_course_dev or persistent H2 demo-v2.

MySQL 8 CHECK enforcement requires 8.0.16+. H2's MySQL compatibility mode is not a replacement for these guarded MySQL assertions.

## Unresolved decisions

1. The approved decision is one immutable payment_settlements row per successfully confirmed Order; do not revise this through UPDATE/DELETE. Financial corrections require separate future records.
2. Define allowed case-resolution transitions and reviewer identity/audit requirements. This proposal records resolution code/time but not staff user ID; add it only after the team decides the authoritative Admin identity contract.
3. Confirm which callback outcomes create reconciliation cases versus only V5 events. Recommended: create a case for late success, mismatch, invalid timestamp, ambiguous status, nonpayable Order, event conflict, and unknown merchant; routine failure and valid success do not create a case.
4. Confirm unknown callback retention duration and whether amount/provider timestamp metadata is sufficient for manual reconciliation.
5. Confirm B/C/E operations are local transactional database writes. If not, define the required outbox actions and retry policy before settlement activation.
6. Confirm how Orders with UNKNOWN attempts are handled by the expiry worker.
7. Confirm the policy when cancellation/expiration wins the Order lock immediately before an otherwise on-time provider success is processed.
8. Confirm whether selected items should remain in Cart after Checkout, or only purchased quantities should later be removed.

No migration, database, source, or test changes were made in this phase.
