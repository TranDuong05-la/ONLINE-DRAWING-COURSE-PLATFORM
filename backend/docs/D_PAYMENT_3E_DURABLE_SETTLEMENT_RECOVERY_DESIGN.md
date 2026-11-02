# D-Payment-3E2: Durable Settlement Outbox Contract

**Status:** this document records the design contract. D-Payment-3E3A has added the V9 migration file and JPA persistence foundation; the migration has not been executed, and no worker or settlement wiring is implemented. Reviewed at branch `feature/member-d-commerce`, HEAD `08d7cee5d5867da2c97d61079326804e0085b0b9`. This document remains untracked in the worktree.

## D-Payment-3E2 freeze candidate

This section is the normative V9 candidate and supersedes conflicting retry, state, and schema options later in the E1 rationale. It is not an approved migration or authorization to activate settlement. The numerical worker settings below are approved only as test-design defaults; they are not production operating limits or activation approval.

### Decisions ready to freeze

- Use a purpose-built local settlement outbox/queue named `payment_settlement_work_items`, keyed by one persisted `payment_event_id`. Do not use V8 reconciliation cases as work items.
- Include `payment_settlement_work_actions` in the same V9 proposal so every human requeue/closure is durably attributable and idempotent. Worker retry transitions are recorded on the work row and metrics; the operator-action table is append-only from the application API.
- Create work only for a newly persisted event that the verified reconciliation service classified as eligible on-time success, within the same REQUIRED transaction as the PaymentEvent and applicable V8 writes. Duplicate callbacks may ensure the same work identity but may not reopen, reset, or overwrite its state.
- The worker is a local database worker. Claim, settlement, participant writes, immutable ledger insertion, and work completion follow separate claim and processing transactions; the final settlement transaction is one local REQUIRED transaction.
- Existing V8 uniqueness (`one settlement per Order/Attempt/Event`) remains the exactly-once backstop. A same-event, same-route ledger replay completes idempotently. A different successful event already on the Order is manual review.
- Keep browser Return out of this path. Persisted `PaymentAttempt.SUCCEEDED` alone never creates work or authorizes settlement.

### Exact V9 schema proposal (documentation only)

The DDL below is a reviewable MySQL 8/InnoDB candidate. It has not been executed. Confirm Hibernate DDL validation and H2 portability when implementation is authorized.

```sql
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
```

The direct Event FK and unique Event key provide referential integrity and one work identity per event. A CHECK cannot inspect the referenced Event, Attempt, Order, or settlement row; eligibility and route checks therefore remain mandatory transactional application checks. `CLOSED_NOT_ELIGIBLE` records an audited work decision only. It does **not** mean the Order is cancelled, unpaid, safe to expire, or safe to release reservations.

The action row stores actor, action key, prior retry counters, transition, reason code, and timestamp. Human actions must be inserted in the same transaction as the state transition. The application repository exposes insert/read only; no update/delete operation is part of the contract. Database-owner privileges can still bypass application append-only behavior, so production role separation and audit retention need confirmation.

Type compatibility target: `payment_events.id` and `users.id` are `INT`, so the direct event and actor FKs above match existing V5/V1 keys. All work and action surrogate IDs are `INT`. V5 event identities use `(provider,event_reference)` and have an existing unique key; work rows need only the `INT payment_event_id` FK and unique key because their route is derived from the immutable event. V5/V8 instants are `DATETIME(6)` and the proposed timestamps use the same precision. Preserve case-sensitive state/token identifiers with the specified binary/ASCII collations. Map `retry_generation` and its snapshot as Java `Long` to signed SQL `BIGINT`; reject an increment at `Long.MAX_VALUE` rather than wrapping. `attempt_count` is bounded 0–8 and fits Java `Short`/`Integer`; verify actual Hibernate validation for MySQL and H2 before migration approval.

For a repeated `(work_item_id, action_key)`, the handler reloads the existing action and compares action type, source/target states, actor, reason, and counter snapshot. Exact match is an idempotent replay; any mismatch is a conflict. The unique key alone prevents duplicate rows but cannot compare request payloads.

### Complete work state machine

| Current state | Trigger | Next state | Required guard |
|---|---|---|---|
| none | Eligible verified Event and atomic enqueue | `READY` | Event, Attempt, Order route and strict deadline evidence validate; no existing settlement or unresolved conflict. |
| `READY` | Atomic claim | `LEASED` | Due time reached; fresh token; attempts incremented in the claim transaction. |
| `RETRY_WAIT` | Atomic claim when due | `LEASED` | Same as READY. |
| `LEASED` | Settlement and completion commit | `COMPLETED` | Exact Event/Attempt/Order evidence; exact same settlement if replay; current lease token and unexpired lease on final fence. |
| `LEASED` | Transient failure with full transaction rollback | `RETRY_WAIT` | Claim count below 8; update is conditional on current token and unexpired lease. |
| `LEASED` | Eight claims exhausted, unrecoverable conflict, or ambiguous evidence | `MANUAL_REVIEW` | No automatic fulfillment or reservation release. Store an allowlisted failure code. |
| `LEASED` | Lease expires before claim limit | `LEASED` with a new token | Current-read claim transaction replaces ownership and increments attempts. Old token is fenced out. |
| `LEASED` | Lease expires after attempt 8 | `MANUAL_REVIEW` | Set `ATTEMPTS_EXHAUSTED` or `LEASE_EXPIRED`; do not reclaim automatically. |
| `MANUAL_REVIEW` | Authorized, idempotent operator requeue | `READY` | Fresh evidence/Order/ledger eligibility check; append `REQUEUE` action; increment retry generation and reset per-generation attempt count atomically. |
| `MANUAL_REVIEW` | Authorized reviewed closure | `CLOSED_NOT_ELIGIBLE` | Append `CLOSE_NOT_ELIGIBLE` action atomically; no automatic Order/claim mutation. |
| `COMPLETED`, `CLOSED_NOT_ELIGIBLE` | Callback replay or worker poll | unchanged | Terminal; duplicate ingress never resets or reopens state. Reopening requires a separately approved audited action type. |

Maximum automatic attempts means **at most 8 committed lease claims per retry generation**, including claims abandoned by process crashes. A human requeue starts a new generation, records the prior counters, and is not an automatic retry. No automatic retry is allowed from MANUAL_REVIEW or CLOSED_NOT_ELIGIBLE.

### MySQL REPEATABLE READ claim and fencing contract

1. Poll on the candidate 10-second interval. In a short transaction, use a **locking current read**, not a plain snapshot read, with `FOR UPDATE SKIP LOCKED`. Use separate queries for each due state (`READY`, `RETRY_WAIT`) and expired `LEASED` rows, each ordered by its indexed due column then `id`; do not combine them in an `OR` predicate. If one claim transaction can take only one category, rotate category priority across poll rounds or prove a bounded-fair policy so a continuous READY stream cannot starve retries or expired leases.
2. Replace/update the row while it is locked: set `LEASED`, new UUID token, bounded worker ID, `lease_until`, `last_claimed_at`, clear `available_at`, increment attempts, and set `updated_at`. Commit this lease before beginning Order work. Under InnoDB REPEATABLE READ, `FOR UPDATE` is a current read; all claim and fence reads must use locking reads/conditional updates rather than relying on an earlier consistent snapshot.
3. The processing transaction must not lock the work row before Order. It reads Event/Work identity, locks Order → Attempt(s) → Settlement → required participants in the shared order, then performs a final conditional work-row update. The final update must check `id`, `state='LEASED'`, exact token, and `lease_until > UTC_TIMESTAMP(6)` using a current write. Affected row count must equal one. Otherwise throw and roll back Order, voucher, C/E, ledger, and work effects.
4. Completion (`COMPLETED`) is written only after all participant and immutable ledger operations succeed, but in the same transaction as them. Because the worker has no earlier work-row lock, a claim transaction may replace a lease token while a slow worker holds the Order; the stale worker's final conditional update then fails and rolls the whole settlement back. The new lease owner can proceed after the Order lock is released.
5. Use database UTC time for lease creation/expiry/fencing; require UTC DB session configuration if using `CURRENT_TIMESTAMP`, or use MySQL `UTC_TIMESTAMP(6)` consistently. Lock/query/transaction timeouts must be finite. Verify driver/H2 behavior separately; do not assume H2 emulates MySQL `SKIP LOCKED` or RR current reads.

### Lease duration and enforceable timeout budget

The earlier 60-second lease candidate was not justified by the repository timeout configuration and is not a hard bound. D-Payment-3E3C.1D adds a disabled-by-default configuration foundation with a 120-second lease candidate and a 60-second Spring settlement transaction timeout. These are implementation/test candidates only, not production-approved guarantees. The lease must cover Order/Attempt/participant locks, database work, ledger insertion, final fencing, and commit acknowledgement. Do not treat Spring's timeout as proof that blocked JDBC I/O, server lock waits, commit, or rollback finish within that interval.

Timeout controls are distinct and all are required: (a) Spring transaction timeout is a transaction deadline hint enforced through the transaction manager and provider; it does not by itself guarantee cancellation of a blocked JDBC call or a bounded server-side lock wait; (b) JDBC statement/query timeout must be configured for repository/JdbcTemplate operations, noting it is per statement and driver behavior must be verified; (c) MySQL session `innodb_lock_wait_timeout` must be finite and configured for application connections, and connection/socket/network timeout must bound stalled I/O. MySQL `MAX_EXECUTION_TIME` is SELECT-specific and is not a substitute for lock-wait or DML limits. Select concrete values so the worst-case sum across sequential lock waits and statements stays below the transaction budget; test these using the production driver and transaction manager.

For guarded test design, provisional controls remain: connection acquisition 2 seconds; connection establishment 3 seconds; MySQL row-lock wait 3 seconds per wait; JDBC statement timeout 5 seconds; Connector/J socket timeout 15 seconds; settlement transaction timeout 60 seconds; lease 120 seconds. Only the transaction timeout, lease duration, and a settlement-owned JdbcTemplate statement timeout are currently scoped in application code. Pool, driver, and MySQL session settings are recorded as deployment candidates and are deliberately not applied to shared infrastructure. Per-wait limits do not bound cumulative waits unless participant counts and total operations are bounded. Verify cancellation, rollback, and commit behavior with the production driver before activation.

The database cannot promise a wall-clock commit timestamp before `lease_until`. The fencing point is the final conditional `UPDATE` that checks the still-current token and unexpired lease against the database clock while acquiring the work-row lock. That lock is held through transaction commit, so a competing claimant cannot replace the token between the successful fence and commit. A contender that waits then sees either `COMPLETED` or a rolled-back lease state. If client connectivity fails during commit and the outcome is unknown, do not report success or immediately re-run side effects: start a fresh transaction and inspect both the immutable settlement and work row before deciding whether to retry.

### Exact retry budget and counter overflow

`attempt_count` is the number of committed lease claims in the current `retry_generation`, not the number of caught exceptions. The initial claim commits count 1. A deadlock/lock timeout or process crash after that commit consumes the claim. Repeated transient failures within one claim do not increment it again. On a transient failure, first let the settlement transaction fully roll back; then, in a separate bounded transaction, schedule `RETRY_WAIT` only if the same token still owns the lease and the count is below 8. A failure or lease expiry at count 8 atomically changes the item to `MANUAL_REVIEW`; there is no ninth automatic claim. This rule applies equally to deadlocks, lock timeouts, crashes, and expired leases.

An operator requeue is one audited compare-and-set from `MANUAL_REVIEW` to `READY`: append the action containing the old generation/count, increment generation, reset `attempt_count` to 0, and make work available in the same transaction. Exact replay of the action key returns the prior result; changed action data conflicts. It creates a new eight-claim budget and is not an automatic retry. Never wrap counters: use signed `BIGINT` for generation and its action snapshot, reject requeue at `Long.MAX_VALUE`, and keep the work in `MANUAL_REVIEW` for a separately approved operational remedy. The bounded attempt counter must never be incremented above 8. These parameter values are test-design defaults only until production SLO and operations approval.

### Duplicate ingress and unique-key race protocol

The verifier and classifier run before any callback is eligible for durable processing. Database uniqueness is the race arbiter; a pre-insert existence check is only an optimization. A uniqueness exception invalidates the current JPA transaction, so no query or “success” result may be produced from it. The transaction owner must allow complete rollback, then start one fresh bounded transaction to resolve the canonical row. No nested `REQUIRES_NEW` lookup is permitted inside a rollback-only caller transaction.

1. **V5 Event insert:** attempt the insert protected by `uk_payment_event(provider,event_reference)`. Only a duplicate violation of that exact key enters replay resolution. After rollback, reload by exact provider plus event reference in a fresh transaction and compare the canonical event fingerprint and the signed/verified route facts (attempt, order, merchant reference, amount, currency, outcome, provider time, and verification identity). Same identity and same facts is an identical replay. Same identity with changed fingerprint/facts follows the existing conflict policy: preserve the original Event, persist the V5 conflict/V8 case under its own exact idempotency key, and never overwrite the original. An unrelated unique, FK, CHECK, or other SQL failure is an error, not a replay.
2. **V8 conflict/case insert:** the unique conflict/case identity is independently recognized. Roll back, reread the canonical conflict/case in a fresh transaction, compare its fingerprint and route, and return idempotent only for exact evidence. Do not catch all `DataIntegrityViolationException`s as duplicates. The existing V5 conflict unique key and V8 reconciliation identity must be explicitly tested by constraint name/SQL state/code on each supported database.
3. **V9 work insert:** insert one row under `uk_payment_settlement_work_event`. Only a duplicate of this exact key is a replay candidate. Roll back the failed enqueue transaction, then in a fresh transaction reload the canonical V5 Event by identity, revalidate that it remains eligible, and load the work row. If absent, a single bounded enqueue retry may occur in a new transaction; if present, verify its `payment_event_id` and immutable Event identity. Never reset state or lease fields. `READY`, `RETRY_WAIT`, or `LEASED` remain as stored; `COMPLETED` stays terminal; `MANUAL_REVIEW` and `CLOSED_NOT_ELIGIBLE` stay terminal. A repeated identical callback does not reopen terminal work.
4. **Changed evidence:** preserve the original Event/work row and write conflict evidence. Do not create a second work item for changed evidence under the same canonical event key, do not replace the Event fingerprint, and do not transition an already terminal work row back to runnable. The worker must recheck unresolved conflicts under the Order lock before settlement. If changed evidence is found before settlement, route the work to manual review using the normal Order-first lock order; if the existing settlement already committed, preserve it and keep the new conflict auditable without changing that settlement.
5. **Caller-owned transaction:** replay resolution is only reported after the transaction that owns the successful canonical read/write commits. If reconciliation joins an outer transaction, propagate a typed retry signal after rollback rather than attempting fresh-transaction work inside it; the outer request/worker boundary owns the one bounded restart. Never acknowledge durable reconciliation before commit.

### Explicit test-design scenarios for retries, ingress, and compatibility

In addition to the base H2/MySQL lists below, test these precise boundaries:

- H2: eight claims exactly, with attempt 8 transient failure and expired lease both entering `MANUAL_REVIEW`; no ninth claim; crash simulation consumes a committed claim; operator requeue creates generation +1/count 0; changed action-key payload conflicts; `Long.MAX_VALUE` generation refuses requeue without mutation; exact duplicate Event/work keys replay; terminal work is not reset; changed evidence preserves original Event and routes conflict; unrelated unique/FK/CHECK exceptions propagate; enqueue failure rolls back Event and work together; simulated unknown commit result is reconciled by fresh ledger/work reads.
- Guarded MySQL `drawing_course_it`: two concurrent identical callbacks racing V5 Event insert yield one canonical Event/work row; changed signed evidence persists one conflict without replacing Event; duplicate V8/V9 exact-key races rollback then reread in fresh transactions; terminal `COMPLETED`/`MANUAL_REVIEW` rows remain unchanged on replay; unrelated unique/FK/CHECK errors are not translated to replay; independent transactions verify claim 8 and no claim 9; REPEATABLE READ locking-current-read sees a winner after wait; lease takeover plus stale token cannot commit; lock timeout/deadlock rolls back all settlement effects before retry metadata; transaction/JDBC/lock/socket timeouts bound an Order-lock wait plus participant processing; verify JDBC types/FKs and Hibernate validation against V1/V5/V8 mappings.

The E2 retry values (8 claims, 10-second poll, 5-second base/15-minute cap full-jitter backoff) remain approved **only as test-design defaults**. The 120-second lease, 60-second transaction timeout, and other D-Payment-3E3C.1D values are configuration candidates for testing only, not production activation settings. Production values require measured transaction bounds, database/driver testing, operational ownership, and explicit approval.

### Retry policy evaluation

| Proposed parameter | E2 recommendation | Freeze condition |
|---|---|---|
| Maximum attempts | 8 committed claims per generation | Candidate default, pending operations approval. Crashes consume a claim because ownership was committed. Human requeue is a new audited generation. |
| Lease duration | 120 seconds | Code default for new claims, test candidate only. Existing persisted leases are unchanged. Production use requires measured processing and commit behavior plus explicit approval. Never extend an active lease without a token-conditional update. |
| Poll interval | 10 seconds | Candidate default, pending operations approval. Make configurable and jitter pollers to avoid synchronized load. |
| Backoff | Exponential with bounded full jitter; base 5 seconds, cap 15 minutes | Candidate parameters, pending operations approval. A transient retry is scheduled only after the settlement transaction has rolled back. |
| Exhaustion | `MANUAL_REVIEW` | Automatic worker stops. No reservation release follows exhaustion. |
| Missing production adapters | Worker bean absent/disabled; rows stay READY | Does not consume attempts. Alert on oldest-ready age and adapter readiness. Never install no-op adapters. |

### Transaction boundary, lock order, and deadlock retry

- **Reconciliation/enqueue:** verify signature and classify first; in one REQUIRED transaction persist the V5 Event, applicable V8 facts, and one eligible work row. Queue insert failure rolls back the successful-event audit transaction; no `RspCode=00` may claim enqueue success unless that whole transaction commits. No work item is created from browser Return or an invalid signature.
- **Claim:** short independent transaction locks only selected work rows, uses `SKIP LOCKED`, changes lease fields, and commits before Order locking.
- **Settle/complete:** one worker-owned REQUIRED transaction, same datasource and transaction manager for every writer, in this order: (1) immutable Event identity read; (2) Order pessimistic lock; (3) referenced PaymentAttempt lock (or all Order Attempts ascending ID if needed); (4) settlement ledger current locking read; (5) voucher definition, per-user usage, claim; (6) Product reservation rows ascending Product ID; (7) Course claim rows ascending Course ID; (8) guarded Order confirmation; (9) immutable settlement insertion; (10) final lease-token conditional work completion update. All writes in steps 5–10 commit or roll back together. No remote call is allowed.
- The current coordinator's existing order is Order → referenced Attempt → settlement lookup → Voucher → Products sorted → Courses sorted → settlement insert. Expiration uses Order → Attempts sorted → settlement lookup → Event/Conflict current reads → Voucher → Products sorted → Courses sorted. E/C must verify their actual row locks can join this order; no design document can assert that for absent adapters.
- Retry a MySQL deadlock/lock timeout only outside the failed processing transaction, after rollback is complete, by conditionally moving the still-owned lease to RETRY_WAIT. Existing `SettlementCoordinator` propagates transient exceptions when it joins an outer transaction; worker orchestration must catch only outside that outer transaction. No nested or unbounded immediate retry loop. If token was lost or lease expired, stale retry metadata update is a no-op.

### Eligibility, races, legacy events, and adapters

- Enqueue only after the existing verifier and reconciliation policy created the persisted, applied `SUCCEEDED` Event with `reason=NONE`, matching fingerprint/route, valid verification reference, matching successful Attempt and pending Order, exact amount/currency, and both provider `occurred_at` and local `received_at` strictly before the persisted exclusive deadline.
- At worker time, repeat all checks under locks. Provider-paid time equal to deadline or receipt time equal to deadline is ineligible for automatic settlement. A delayed worker may settle after the deadline only if both persisted evidence instants were strictly before it and the Order remains pending; worker execution time does not replace those evidence instants.
- Exact existing settlement for the same Order/Attempt/Event makes the item COMPLETED without repeating participant effects. Settlement by another Event/Attempt, an expired/cancelled/non-pending Order, mismatched evidence, timestamp uncertainty, or conflicting callback goes to MANUAL_REVIEW. Failure/unknown events are never auto-settled. Review/closure never releases voucher, inventory, Course claims, or Order reservations.
- Ingress and expiry serialize on the Order lock: if valid callback+work commits first, expiry sees success evidence and refuses release; if expiration commits first, the later callback is late/nonpayable and must not enqueue. A worker racing expiry rechecks under the Order lock. Two Events for one Order may produce two work rows, but V8 unique Order settlement lets only one settle; the other is manual review.
- Pre-V9 events are **not** automatically backfilled by migration. Provide a dry-run report of candidate Event IDs/reason counts; after explicit approval, a bounded application command may revalidate each event, route, Order state, strict times, conflict/case state, and settlement absence before idempotently inserting one work row. Exclude late, ambiguous, conflicted, already settled, non-pending, or mismatched Events. Backfill itself must be restartable and rate bounded.
- Missing Course/Inventory/Voucher/ledger production adapters disable worker registration. The queue may accumulate READY work; no attempts are consumed until a worker with all required real adapters starts. If any participant is remote/nontransactional, this local atomic contract is blocked and requires a separate downstream outbox/compensation design.

### Manual review and H2/MySQL verification contract

Operator actions require authenticated Admin/authorized staff, authorization checked on every call, idempotency key, allowlisted reason, actor ID, and an append-only action row committed with the state transition. REQUEUE must revalidate evidence and Order/settlement state under the normal lock order; it must not edit V5/V8 evidence or an existing settlement. A close as not eligible is a workflow disposition only and must not imply unpaid/cancelled status or release resources. Retention and who owns the review queue require approval.

H2 tests should cover DDL checks/FKs, exactly-one work row for callback replay, verified-only enqueue, atomic Event+outbox rollback, every legal/illegal transition, claim limits, stale-token fencing, same-settlement replay, competing settlement review, and operator-action idempotency/authorization. H2 tests do not prove InnoDB concurrency.

Guarded MySQL `drawing_course_it` tests should cover concurrent `SKIP LOCKED` claimers, no duplicate claim ownership, REPEATABLE READ current-read behavior after waiting, expired-lease takeover, stale worker commit rejection with full rollback, deadlock/lock-timeout rollback then bounded retry metadata, Event+outbox atomicity, competing work items for one Order, unique settlement enforcement, UTC `DATETIME(6)`, and no resource release on manual/exhausted work. Run only with separate explicit approval and existing exact target guards.

### D-Payment-3E3A.1 migration compatibility audit and guarded MySQL plan

**Source comparison:** V9 uses `INT` for IDs and references, matching `payment_events.id`, `users.id`, and the existing V1-V8 entity keys. It uses signed `BIGINT` for `retry_generation`, signed `SMALLINT` for the 0-8 attempt counter, `DATETIME(6)` for every instant, the unique Event identity and action idempotency key, the three due/review indexes and action-history index, and the event/work/user `ON DELETE RESTRICT` foreign keys from the contract. Its MySQL CHECK expressions and lease-field branches match the normative state/field rules. The JPA fields map these SQL types to `Integer`, `Long`, `Short`, converted `Instant`, String enums, and `CHAR(36)` action key. Hibernate's `@Check`, `@UniqueConstraint`, `@Index`, and association mappings mirror the constraints and indexes for generated H2 DDL. MySQL charset/collation details are necessarily defined by V9 SQL; Hibernate schema validation does not verify all collation semantics. The SQL defaults `attempt_count=0` and `retry_generation=0` are not declared with Hibernate `@ColumnDefault`; the `ready(...)` factory explicitly initializes both values, so normal JPA inserts do not depend on those database defaults. Hibernate validation and current H2 tests do not verify the MySQL defaults; include them in `information_schema`/insert-default checks during guarded verification.

**H2 migration verdict:** do not apply V1-V9 through Flyway on H2. The chain is authored for MySQL/InnoDB. V1 explicitly uses MySQL's `utf8mb4_0900_as_ci` collation (as well as `utf8mb4_bin`, table character-set clauses, and `ENGINE=InnoDB`); later migrations continue that dialect and include MySQL-specific DDL. The ordinary H2 profile deliberately disables Flyway and uses Hibernate `create-drop`. The existing `PaymentSettlementWorkPersistenceH2IntegrationTest` therefore verifies entity-generated tables and checks only; it does not execute or validate Flyway V9, V1-V8 compatibility, MySQL collations, or InnoDB behavior. No H2 Flyway test is added because it would require dialect rewriting or a synthetic schema and would not test the deployed migration. A fresh H2 database is still appropriate for isolated JPA mapping/constraint behavior.

**Guarded MySQL verification plan (not run in D-Payment-3E3A.1):** use only the existing `mysql-it` profile and its pre-context guard. Require `MYSQL_IT_URL=jdbc:mysql://127.0.0.1:3306/drawing_course_it`, `MYSQL_IT_DATABASE_ACK=drawing_course_it`, nonblank `MYSQL_IT_USERNAME`, a defined `MYSQL_IT_PASSWORD`, the `-Pmysql-it` property, active `mysql-it` Spring profile, Flyway enabled with `clean-disabled=true`, Hibernate `ddl-auto=validate`, and the effective datasource URL exactly matching the target. Do not set `DB_URL` as a substitute. Run from `backend` only after separate explicit authorization and secure credential entry: `.\mvnw.cmd -Pmysql-it verify`.

Before migration, require Flyway V1-V8 success and no pending version other than V9. After startup, require successful Hibernate validation and Flyway V1-V9 success. Query `information_schema` to verify both V9 tables, `INT` FK columns, signed `BIGINT` generation, signed `SMALLINT` attempts, SQL defaults of zero for both counters, `DATETIME(6)` precision, each named unique/FK/CHECK/index, and FK target/column order. Attempt bounded invalid state, lease-field, action-transition, negative generation, attempt-count 9, and orphan-FK rows and assert the exact expected MySQL constraint/error. Then run all established guarded MySQL schema, checkout/voucher, reconciliation, settlement/expiration suites plus the new V9 schema assertions; preserve their exact test counts and report failures by suite. Do not use `clean`, `repair`, drop, truncate, or reset. MySQL DDL may auto-commit: if Flyway fails partway, stop, capture migration/history and schema state, and require DBA review. Do not claim rollback of DDL; only transactional test fixture writes can be rolled back. No production endpoint, worker, or settlement flow is activated by this verification.

### Assumptions and blockers requiring approval

**Ready-to-freeze design choices:** table names/column meanings above; one work row per eligible Event; no V9 data backfill in migration; Event/Outbox atomic enqueue; two-transaction claim/process split; final token/expiry fence; one local REQUIRED settlement transaction; no worker when real adapters are missing; manual actions audited in a second V9 table.

**Assumptions to confirm before activation:** MySQL version supports `SKIP LOCKED` (MySQL 8); application and DB UTC handling; the provisional 120-second lease and 60-second transaction setting are supported by measured transaction, driver, lock-wait, commit, and rollback behavior; 8 attempts are claims per generation; 10-second poll and 5-second-to-15-minute full-jitter backoff fit operational load; Admin/Staff user identity maps to V1 `users.id` and authorization policy.

**Approval blockers:** authorization to create V9 and run guarded verification; merchant-specific VNPay acknowledgment/retry behavior after atomic enqueue failure; C/E production adapters and shared transaction/lock contract; whether manual work actions and evidence retention meet audit/privacy requirements; whether pre-V9 candidates should be backfilled after dry-run review; lease timeout/backoff values after measured settlement transaction bounds. Until resolved, no worker or production settlement activation is authorized.

## D-Payment-3E1 architecture and rationale (non-normative)

The following E1 material records source findings and design reasoning. Any earlier candidate state names, columns, limits, or retry instructions in it are superseded by the normative E2 contract above. In particular, E1 `REQUIRES_REVIEW` maps to E2 `MANUAL_REVIEW`; E1 `NOT_ELIGIBLE` maps only to a reviewed `CLOSED_NOT_ELIGIBLE` disposition, never to automatic resource release. E1's 12-claim proposal is withdrawn in favor of the E2 proposal of 8 claims per audited retry generation.

## 1. Current architecture assessment

### Verified components

- Flyway currently contains V1–V8; no V9 settlement-recovery table exists.
- V5 `payment_attempts` stores a payment attempt snapshot and status. `(provider, merchant_reference)` and `(provider, transaction_reference)` are unique. It references an Order and user.
- V5 `payment_events` is the durable callback audit. It records the route, provider transaction reference, amount/currency, `outcome`, provider `occurred_at`, local `received_at`, payload digest, verification reference, fingerprint, before/after payment statuses, disposition, review reason, and observed Order status. `(provider, event_reference)` is unique. Its FKs route to Attempt, Order and user.
- V5 `payment_event_conflicts` retains a changed fingerprint for an existing event. It does not overwrite the original event.
- V8 `payment_settlements` is the immutable successful settlement ledger. `order_id`, `payment_attempt_id`, and `payment_event_id` are each unique. Composite FKs enforce that the Attempt and Event belong to the same Order/Attempt route.
- V8 `payment_reconciliation_cases` is a bounded review-case table with a unique evidence identity and route CHECK/FK constraints. Its statuses are `OPEN`, `RESOLVED`, and `DISMISSED`; it is not a settlement work queue and has no retry/lease fields.
- `VnPayIpnReconciliationService.reconcile(VerifiedVnPayCallback)` verifies the callback and writes V5 audit plus applicable V8 cases inside a REQUIRED transaction. It retries a recognized unique-insert race only after its failed transaction has rolled back and only when there is no caller-owned transaction. Its return value contains an event ID and classification, but is transient process data.
- `VnPayIpnController` currently returns `RspCode=00` after reconciliation returns; it does not invoke settlement. Therefore callback audit can commit while no later component is responsible for settling an eligible event.
- The class found is `com.drawingcourse.backend.service.settlement.SettlementCoordinator`; no `OrderPaymentSettlementCoordinator` class exists at this HEAD. `SettlementCoordinator.settle(IpnReconciliationResult)` reloads the persisted event, locks Order then Attempt, checks the ledger, validates the audit and strict deadline evidence, confirms the Order, calls Voucher/Product/Course participants, and inserts the settlement row in one REQUIRED transaction. It does not persist or claim retry work.
- Settlement registration is conditional on `app.payment.settlement.enabled`, VNPay being enabled, and the required repository/participant ports being present. Production Course and Inventory confirmation adapters are not currently available. No durable worker or scheduler is registered by default.
- `SettlementCoordinator` returns `RETRYABLE_FAILURE` for `TransientDataAccessException` only after its own transaction has rolled back. If it joined a caller transaction, it propagates the exception because it cannot declare the caller's rollback complete. This distinction should be preserved by a worker.
- `OrderExpirationCoordinator` uses the same Order-first convention. It locks Order, all Attempts by ID, the settlement row, then event/conflict evidence; if successful, unknown, conflicting, or otherwise unresolved evidence exists, it does not expire or release reservations. Clean unpaid expiry is atomic with reservation releases.

### Evidence required before settlement

The queue payload must identify a persisted `payment_event_id`; it must not carry a trusted `success=true` flag or mutable callback facts. Before effects, the settlement worker/coordinator must reload and confirm all of the following:

1. Event, Attempt, and Order exist and their persisted IDs, provider, merchant reference, transaction reference, user, and Order route agree.
2. The event is the canonical persisted event, with a matching fingerprint and `outcome=SUCCEEDED`, `disposition=APPLIED`, `reason=NONE`, `after_status=SUCCEEDED`, and the verifier/timestamp reference `vnpay-hmac-sha512;paydate=valid`.
3. The Attempt is `SUCCEEDED`, has no review reason, its succeeded timestamp matches the provider event time, and its amount/currency snapshots match the Order.
4. Provider payment time **and** local IPN receipt time are each strictly earlier than the persisted exclusive Order deadline. Equality is expired. The event's provider timestamp is mandatory and must have passed strict parsing and range checks.
5. Order remains `PENDING_PAYMENT`; the ledger has no settlement for that Order, unless the exact same event/Attempt/Order is already settled, in which case this is idempotent replay.

`PaymentAttempt.SUCCEEDED` alone is not settlement evidence. V5 Event identity plus the persisted audit decision and Order/Attempt snapshots are the durable source. V8 settlement uniqueness is the final exactly-once backstop.

## 2. Queue versus transactional outbox

| Option | Benefits | Risks / fit |
|---|---|---|
| Scan `payment_events` for unprocessed successes | No new row at reconciliation time; can recover legacy rows | Requires a separate processed marker or anti-join; races duplicate workers; every scan must re-evaluate many immutable events; not atomic with “already processed” state unless a durable claim row is added. |
| Generic transactional outbox | Standard atomic publication pattern; useful when publishing to a broker or remote fulfillment system | Adds broker/publisher and message semantics that are not currently present. Settlement itself is local database work and needs an Order-aware lease/terminal state, so a generic message table would still need claim/processing state. |
| **Purpose-built settlement work/outbox row (recommended)** | Inserted atomically with an eligible successful Event; one unique row per Event; supports leases, backoff, terminal review and operational visibility | Requires V9 and a worker. It must not be mistaken for a guarantee that missing Course/Inventory adapters can be bypassed. |

The recommended table is a database outbox and a worker queue for one local operation. It should not send a VNPay request or call remote fulfillment APIs. If Course or Inventory confirmation eventually requires remote work, that downstream work needs its own outbox/compensation contract; it cannot be included in the local atomic settlement claim.

## 3. Proposed state machine

One work item is keyed by one immutable PaymentEvent. Suggested states:

| State | Meaning | Allowed next state |
|---|---|---|
| `READY` | Eligible event and work row committed; awaiting a worker | `LEASED` |
| `RETRY_WAIT` | A prior technical transaction rolled back; retry is scheduled | `LEASED` when due |
| `LEASED` | Worker owns a time-bounded, token-fenced claim | `COMPLETED`, `RETRY_WAIT`, `REQUIRES_REVIEW`, `NOT_ELIGIBLE`; expired lease may be reclaimed |
| `COMPLETED` | Matching immutable settlement and confirmed Order committed in the same transaction as work completion | Terminal |
| `REQUIRES_REVIEW` | Evidence, routing, competing settlement, or participant state is ambiguous/contradictory | Terminal pending explicit reviewed action |
| `NOT_ELIGIBLE` | Persisted event is definitively not settlement-eligible | Terminal |

No separate `PROCESSING` state is required if `LEASED` represents active ownership. A lease expiry does not mean the payment failed; it makes the row claimable again. Retry budget exhaustion moves technical failures to `REQUIRES_REVIEW` (or a separately approved exhausted state), never to `NOT_ELIGIBLE`.

## 4. V9 schema proposal (not migration SQL)

V9 is necessary if automatic crash/transient recovery is required. V5/V8 do not durably record that an eligible event still needs settlement, a lease, next retry time, or worker outcome. A candidate single-table design is:

| Column | Candidate type | Purpose / rule |
|---|---|---|
| `id` | `INT AUTO_INCREMENT` | Primary key, consistent with current payment tables. |
| `payment_event_id` | `INT NOT NULL` | Immutable work identity; UNIQUE and FK to `payment_events(id)`. Do not duplicate Order/Attempt IDs; derive route through the immutable Event. |
| `state` | `VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL` | CHECK allowlist of states above. |
| `attempt_count` | `INT NOT NULL DEFAULT 0` | CHECK nonnegative; increment on each committed lease claim. |
| `next_attempt_at` | `DATETIME(6) NOT NULL` | UTC availability for first attempt/backoff. |
| `lease_token` | `CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL` | Fresh random fencing token per claim; never a provider secret. |
| `lease_owner` | `VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL` | Bounded worker instance identifier for diagnosis. |
| `lease_until` | `DATETIME(6) NULL` | UTC lease expiry. |
| `last_attempt_at` | `DATETIME(6) NULL` | UTC worker attempt time. |
| `last_failure_code` | `VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL` | Bounded allowlisted technical/business category, never raw exception text. |
| `created_at`, `updated_at` | `DATETIME(6) NOT NULL` | UTC lifecycle timestamps. |
| `completed_at` | `DATETIME(6) NULL` | Set only for `COMPLETED`. |

Candidate constraints and indexes:

- `PRIMARY KEY (id)`; `UNIQUE (payment_event_id)`; `FOREIGN KEY (payment_event_id) REFERENCES payment_events(id) ON DELETE RESTRICT`.
- `KEY ix_settlement_work_available (state, next_attempt_at, id)` for ordered claims; `KEY ix_settlement_work_lease (state, lease_until, id)` for abandoned lease recovery/monitoring.
- CHECK state allowlist; `attempt_count >= 0`; lease token/owner/until all present iff `state='LEASED'`; `completed_at` present iff `state='COMPLETED'`.
- If terminal states require a `last_failure_code`, add an explicit CHECK for those cases. Avoid a CHECK that attempts to validate another table's event eligibility; MySQL CHECK cannot enforce cross-table business predicates.
- Use the project's UTC `DATETIME(6)` converter convention. Confirm MySQL 8 enforced CHECK behavior and Hibernate schema validation in guarded `drawing_course_it` after V9 is approved; isolated H2 tests are supplemental, not proof of MySQL lease/concurrency semantics.

The event eligibility test is transactional application policy and must be rechecked on every worker attempt. The unique Event key prevents duplicate queue rows for one callback event. It does not prevent multiple successful events for one Order from producing multiple rows; `uk_payment_settlement_order` and Order locking arbitrate that race.

Optional later addition: an append-only `payment_settlement_work_attempts` table for full attempt history. It is not required for the minimum queue if bounded aggregate fields, metrics, and the immutable payment evidence are sufficient. Decide retention/audit needs before adding it.

## 5. Atomic enqueue transaction and callback acknowledgment

When reconciliation writes a new eligible successful V5 Event, it must insert the V9 work row **inside the same REQUIRED transaction** that persists the Event and any V8 case facts. The transaction commits both or neither. Queue insertion must follow full eligibility checks; ambiguous, late, mismatched, conflicting, failure, unknown-reference, or invalid-timestamp evidence gets no settlement work item.

For a duplicate callback, reconciliation loads the canonical Event and performs an idempotent ensure-work operation for that same Event only if its persisted facts still satisfy eligibility. The `UNIQUE(payment_event_id)` handles races; a duplicate-key race is reread after rollback using a fresh transaction, following the V5/V8 writer pattern. Do not treat arbitrary database constraint failures as idempotent replay.

After this design is implemented, HTTP `RspCode=00` may mean that verified callback evidence **and its required settlement work row** committed. It still does not mean that settlement, Order confirmation, or fulfillment completed. If required enqueue persistence fails, the whole reconciliation transaction rolls back and the controller must not acknowledge `00`. Do not alter the existing provider code mapping without confirming it against the merchant-specific VNPay contract.

Existing pre-V9 eligible events require an explicit migration/backfill decision: a bounded idempotent scanner can enqueue only Events that pass the same strict persisted checks. Backfill must not replay late, conflicting, or ambiguous events into automatic settlement.

## 6. Claim, lease, retry, and crash recovery algorithm

1. **Claim briefly:** In a short transaction, select due `READY`/`RETRY_WAIT` rows or expired `LEASED` rows, ordered by `(next_attempt_at,id)`, with row-level skip-locked semantics where supported. Set `LEASED`, a fresh token, owner, lease deadline, attempt count, and last-attempt timestamp; commit before doing settlement work. Never hold the queue-row lock while waiting for an Order or provider.
2. **Process one event:** Start a separate local transaction. Reload the Event and route, then take the shared Order-first locks. Verify the claim token is still current using a fenced conditional completion/update. Revalidate all callback, amount, currency, and strict-deadline evidence. Invoke the transaction-bound SettlementCoordinator; do not trust a previous in-memory `IpnReconciliationResult` or the work row alone.
3. **Complete atomically:** If settlement returns `SETTLED` or exact-match `ALREADY_SETTLED`, update work to `COMPLETED` in the same transaction that contains Order confirmation, participant confirmation, and immutable V8 settlement insertion. If the final lease-token/state update affects zero rows, throw so settlement rolls back; this prevents an expired lease holder from committing after another worker took ownership.
4. **Retry technical failure:** Deadlock, lock timeout, or known transient database unavailability rolls back the complete settlement transaction first. Outside that rolled-back transaction, a bounded retry transaction records a safe failure code, clears lease fields, and sets `RETRY_WAIT` with exponential backoff plus jitter, conditional on the same fencing token still owning the row. If another worker already reclaimed it, the stale worker makes no queue update. If process crash occurs before rescheduling, the committed lease expires and another worker reclaims it.
5. **Stop automatic retry:** Missing/contradictory Event, invalid timestamp, expired/nonpayable Order, different existing settlement, unavailable/mismatched reservation, or a definitive participant rejection becomes `REQUIRES_REVIEW` or `NOT_ELIGIBLE` according to a reviewed typed outcome. Never relabel a business conflict as transient.
6. **Bound work:** Set a configurable maximum attempt count, lease duration, backoff cap, and maximum processing age. A proposed initial operational policy is 12 claims with delays capped at 15 minutes, then `REQUIRES_REVIEW`; these values need operator approval and load testing. Use no unbounded loop or hot retry.

The worker must own the transaction whose rollback it reports. `SettlementCoordinator` already propagates transient exceptions when it joins an outer transaction; the worker catches them only after its outer transaction has rolled back, then updates retry metadata separately. Never catch a transient error inside the active transaction and return a successful/retryable result before rollback is complete.

## 7. Lock ordering and expiration interaction

Preserve the existing Order-first lock convention. Candidate shared mutable lock order:

1. Order pessimistic write lock.
2. Relevant PaymentAttempt row (or all Order Attempts ascending ID where a flow needs all attempts).
3. Existing settlement lookup/lock.
4. Voucher definition, per-user usage and claim.
5. Product reservations in ascending Product ID.
6. Course claims in ascending Course ID.
7. Insert immutable settlement ledger; update work item using its fencing token as the final queue write.

Current `SettlementCoordinator` reads Event identity before locking and then locks Order → referenced Attempt → settlement lookup → Voucher → Product IDs → Course IDs → ledger insert. Expiration locks Order → all Attempts → settlement lookup → current Event/Conflict reads → Voucher → Product IDs → Course IDs. Reconciliation locks Order → Attempt before writing event/case. Since queue claiming commits before Order locking and never holds its DB lock across processing, it introduces no queue→Order lock cycle. The actual C/E adapter locks remain unverified; Member C/E must confirm they can obey this compatible order.

Race rules:

- If eligible reconciliation obtains the Order lock first, it commits Event+work together. Expiration later sees success evidence and refuses automatic expiry/release; worker can settle later, even after the deadline, because both signed provider time and receipt were strictly before it.
- If expiration obtains the Order lock first and commits expiry, later callback evidence is late/nonpayable and receives a review case, not a settlement work item.
- If the worker and expiration race, the Order lock serializes them. Expiration must see the committed success Event and avoid release; worker must recheck Order and settlement evidence under lock.
- A work row can be leased more than once over time. Only the current fencing token may mark it complete. Order lock plus unique settlement Order key prevents duplicate business effects even if a lease expires mid-attempt.
- Two distinct success Events for one Order can create two work rows. The first valid transaction wins the Order lock and unique settlement; the other becomes a settlement conflict/review item without overwriting the first ledger row.

## 8. Failure and recovery matrix

| Situation | Work state / action | Automatic Order or reservation effect? |
|---|---|---|
| Eligible Event and outbox insert commit | `READY` | None until worker runs. Existing Order reservations remain held. |
| Event audit or queue insert fails | Whole reconciliation transaction rolls back; no success acknowledgment | None. Provider retry policy is merchant-specific and must not be invented. |
| Worker crashes after claim, before processing | Lease expires; reclaim | None. |
| Worker crashes during settlement before commit | Database rolls back; lease later expires/retries | No partial local Order/claim/ledger effects if all adapters share the transaction manager. |
| Deadlock/lock timeout/transient DB error | Roll back, then bounded `RETRY_WAIT` | Retry only after rollback; no partial local effects. |
| Exact same Event already has exact settlement | `COMPLETED` (`ALREADY_SETTLED`) | No repeated participant effects. |
| Another Event/Attempt already settled the Order | `REQUIRES_REVIEW` | Never overwrite or create a second settlement. |
| Event/Attempt/Order mismatch, late timestamp/receipt, amount/currency mismatch, UNKNOWN/conflict | `NOT_ELIGIBLE` or `REQUIRES_REVIEW` | No automatic confirmation, claim confirmation, or release. Preserve existing evidence/case. |
| C/E production adapter missing at startup | Worker/coordinator stays unregistered; rows remain `READY`, alert on backlog | Do not substitute no-op adapters; do not confirm partial fulfillment. |
| C/E operation returns authoritative terminal business rejection | `REQUIRES_REVIEW` unless approved contract says definitively `NOT_ELIGIBLE` | Transaction rolls back; preserve reservations and evidence for review. |
| C/E operation is remote/nontransactional | Not safe for this atomic design | Require a separate transactional integration or downstream outbox/compensation design before enabling settlement. |
| Retry budget exhausted | `REQUIRES_REVIEW` with bounded failure code and durable Event/Order references | No auto-expiration or reservation release while success evidence is unresolved. |

## 9. Idempotency, observability, and manual recovery

- Queue identity is `payment_event_id`; database uniqueness makes repeated callback enqueue idempotent.
- Settlement identity is already protected by unique Order, Attempt and Event keys in V8. Same exact settlement replays as complete; a different successful event is preserved for review.
- Lease tokens fence stale workers. Claim attempt count increments in the committed claim transaction. A worker that loses its token cannot commit settlement plus completion.
- Metrics: READY count/oldest age, RETRY_WAIT count, expired leases, processing latency, retry histogram by allowlisted category, completed count, review count, and enqueue-to-settle duration. Alert on sustained backlog, expired lease growth, retry exhaustion, and manual-review age.
- Logs should carry work ID, Event ID, Order ID, attempt number, and bounded failure code only. Never log full callback parameters, signatures, secrets, payment URLs, raw exception messages, or card/account data.
- Manual recovery should be an authenticated, audited application operation that rechecks persisted evidence and writes an operator identity/reason. Do not recommend direct SQL edits, rewriting Events, or mutating immutable settlements. Decide whether V9 needs a separate append-only operator-action table; the minimum work table alone cannot provide a full human-action audit trail.

## 10. Integration dependencies and implementation phases

1. **D-Payment-3E2 schema approval:** approve work-item name/state vocabulary, retry budget/backoff/lease duration, legacy-event backfill, manual-action audit/retention, and provider acknowledgment interaction. Then prepare V9 only after design approval.
2. **Persistence phase:** add JPA mapping/repository and H2 tests for route FK, one row per Event, state CHECKs, lease consistency, transition fencing, rollback, and duplicate enqueue. Guarded MySQL tests on `drawing_course_it` must prove `SKIP LOCKED`/claim concurrency, lease recovery, uniqueness, deadlock handling, and actual `DATETIME(6)` mapping. Do not infer MySQL behavior from H2.
3. **Worker/coordinator phase:** implement one bounded worker, with feature flag off, transaction ownership explicit, typed terminal-vs-transient results, retry scheduling after rollback, and metrics. Keep the IPN controller from calling settlement inline.
4. **C/E integration gate:** require real Course ownership and Product inventory confirmation adapters with same datasource and transaction manager, idempotent per Order semantics, and agreed row locks. If any participant is remote, stop the atomic workflow and design a downstream outbox before enabling.
5. **Recovery/operations phase:** implement authenticated operator review/re-drive only with audit and runbook. Decide callback acknowledgment policy for durable queueing and transient queue insert failures with VNPay/account owner.
6. **Activation review:** verify complete guarded MySQL concurrency/rollback suite, then obtain separate approval before enabling `app.payment.settlement.enabled`, `VNPAY_ENABLED`, or `VNPAY_IPN_ENABLED` in any environment. Browser return remains non-authoritative.

## 11. Decisions requiring approval

- Confirm the exact name and whether the V9 table is a purpose-built outbox/work queue or generic outbox. Recommendation: `payment_settlement_work_items`, one row per eligible persisted Event.
- Approve state vocabulary, maximum retries, lease duration, backoff cap/jitter, processing-age threshold, and manual-review transition.
- Decide whether old eligible V5 events are backfilled at migration/deployment or intentionally excluded from auto-recovery.
- Decide if bounded last-error fields and metrics satisfy audit needs, or if append-only work-attempt/operator-action records are required.
- Confirm whether `00` means “callback and settlement work durably accepted” for the actual VNPay merchant/version, and what provider retry response is required if durable enqueue fails. Public sandbox docs do not establish account-specific behavior.
- Confirm C/E datasource/transaction manager, lock order, reservation confirmation idempotency, and that all writes can join one local REQUIRED transaction. Current production adapters are unavailable.
- Confirm operational ownership for manual-review cases, backlog alert response, retention, and operator privileges.
- Confirm whether future remote fulfillment requires a separate outbox and what compensation/reconciliation policy applies.

**Readiness verdict:** V9 is required for durable automatic settlement recovery. The design is now implementation-ready as a candidate contract, but V9 coding still requires explicit approval of the proposed schema and operational values. Production worker/settlement activation additionally remains blocked on measured timeout limits, merchant-specific acknowledgment/retry semantics, audited legacy-event handling, operational review ownership, and real C/E adapters in the same transaction manager. This design does not authorize a migration, database operation, MySQL run, or endpoint activation.
