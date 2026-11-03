# D-Payment-3E3C.5 — Production Readiness and Activation Contract

## Purpose and verdict

This contract separates the settlement foundation that exists today from controls required before production activation. It does not authorize activation.

**Verdict: NO-GO for production activation.** The durable V9 enqueue, claim/lease, fenced settlement, bounded retry, recovery inspection, and explicit worker orchestration foundations are implemented. The user-reported guarded MySQL regression is 66 tests, 0 failures, 0 errors. Activation remains blocked by missing Course, Product, and Voucher production transaction participants; missing worker construction/polling/shutdown wiring; missing operational metrics and alerts; and missing authorized atomic operator recovery service.

## Evidence boundary

### Verified implementation facts

- `VnPayIpnReconciliationService` inserts one READY work item for an accepted payment event in its existing local transaction and does not reopen an existing item.
- V9 defines unique work identity by payment event, bounded attempts `0..8`, READY/RETRY_WAIT/LEASED/COMPLETED/MANUAL_REVIEW/CLOSED_NOT_ELIGIBLE states, due/lease/review indexes, lease metadata, and append-only operator-action rows.
- `SettlementWorkClaimService` uses a short transaction and returns only after claim commit. It selects READY, due RETRY_WAIT, or expired LEASED work using `FOR UPDATE SKIP LOCKED` and database UTC time.
- `SettlementCoordinator.executeClaimedWork` rejects an ambient transaction, runs each item in its own bounded transaction, performs fresh-transaction inspection after execution failure, and never blindly replays an uncertain commit.
- Completion, retry, exhaustion, and manual-review writes are conditional on current work state, lease token, and database-time lease validity.
- The settlement lock/participant order is Order, PaymentAttempt, Voucher, Product IDs ascending, Course IDs ascending, settlement ledger, then the final work-item fence.
- `SettlementWorkerOrchestrator` is bounded to 64 items, processes items independently, returns typed counts, and continues after an individual item failure.
- The orchestrator is deliberately not a Spring bean. No production source constructs it, invokes `runBatch`, schedules it, starts a thread, or exposes an endpoint.
- `app.payment.settlement.enabled` defaults to false in the bound properties and gates optional coordinator construction. No settlement-worker flag or worker runtime properties exist yet.
- `JpaSettlementLedgerAdapter`, work repositories, and claim service exist. No production implementation of `CourseOwnershipConfirmationPort`, `InventoryReservationConfirmationPort`, or `VoucherConsumptionPort` exists.
- V9 action entities and an append-only repository exist, but no service atomically performs an authorized REQUEUE or CLOSE_NOT_ELIGIBLE transition.
- Existing timeout defaults are lease 120 seconds, settlement transaction 60 seconds, claim transaction 5 seconds, and JDBC statement 5 seconds. Connection, MySQL lock, and socket timeout fields are documented candidates and are not all wired to the shared datasource/driver.

### Proposed future controls

Every configuration, scheduler/poller, health check, metric, alert, operator workflow, and rollout step below is a proposal. None is currently active unless explicitly identified above as implemented.

## Current readiness matrix

| Capability | Status | Activation consequence |
| --- | --- | --- |
| Atomic durable enqueue | Ready, guarded-test verified | Retain; payment acknowledgement must never depend on worker availability. |
| V9 schema and constraints | Ready in source and guarded-test verified | Production artifact must contain and successfully validate V9 before activation. |
| Claim, lease, SKIP LOCKED | Ready, guarded-test verified | Use only with supported MySQL and database UTC fencing. |
| Claimed settlement and final fence | Ready, guarded-test verified | Preserve the established lock order and single local transaction. |
| Retry/exhaustion policy | Ready | Fixed maximum is eight attempts; full-jitter delay starts at 5 seconds and caps at 15 minutes. |
| Ambiguous-commit inspection | Ready | UNKNOWN is an orchestration observation, not a persisted V9 state. |
| Worker orchestration API | Ready but inert | Must be explicitly wrapped; do not make the domain orchestrator itself a scheduler. |
| Settlement ledger adapter | Ready | Must use the same local transaction manager and datasource. |
| Course claim confirmation | Missing production adapter | Hard NO-GO. |
| Product reservation confirmation | Missing production adapter | Hard NO-GO. |
| Voucher consumption | Missing production adapter | Hard NO-GO. |
| Worker bean/poller/shutdown | Missing | Hard NO-GO. |
| Monitoring and alerting | Missing | Hard NO-GO. |
| Authorized operator recovery | Schema only; service/security absent | Hard NO-GO. |
| Runtime timeout alignment | Partially represented, not fully wired | Validate deployment-level pool/driver/database settings before canary. |
| Production activation | Disabled | Must remain disabled until every prerequisite is signed off. |

## Runtime dependency graph

```text
Future disabled-by-default poller
└── SettlementWorkerOrchestrator (not currently a bean)
    ├── SettlementWorkClaimService
    │   ├── PaymentSettlementWorkClaimRepository (JDBC)
    │   ├── SettlementTimeoutProperties
    │   ├── SettlementRetryPolicy
    │   └── application PlatformTransactionManager
    └── SettlementCoordinator
        ├── OrderRepository
        ├── PaymentAttemptRepository
        ├── PaymentEventRepository
        ├── PaymentSettlementWorkItemRepository
        ├── PaymentSettlementWorkClaimRepository
        ├── JpaSettlementLedgerAdapter
        ├── VoucherConsumptionPort                 [production adapter missing]
        ├── InventoryReservationConfirmationPort   [production adapter missing]
        ├── CourseOwnershipConfirmationPort        [production adapter missing]
        ├── UTC Clock
        ├── SettlementTimeoutProperties
        └── the same application PlatformTransactionManager

All persistence and participant adapters
└── the same production MySQL datasource and physical local transaction
```

The three missing participants must be local, idempotent transaction participants. They must join the already active settlement transaction, must not independently commit, and must not make irreversible remote calls. Course confirmation must atomically confirm the Order's reserved claims; Product confirmation must atomically confirm the Order's reservations; Voucher consumption must atomically consume the Order's reserved claim. Each must verify Order/user/item identity and be idempotent by durable uniqueness, not process memory.

## Datasource and transaction contract

Activation requires all of the following:

1. One authoritative MySQL datasource for Orders, attempts, payment events, work items/actions, vouchers, Product reservations, Course claims/enrollments, and the settlement ledger.
2. One Spring `PlatformTransactionManager` that coordinates both JPA repositories and the JDBC claim repository against that same datasource. There must be no ambiguous multiple-manager injection.
3. Participant methods must require an existing transaction and fail if none exists. Independent `REQUIRES_NEW`, autonomous commits, asynchronous side effects, and remote best-effort calls are forbidden.
4. Claim transactions must commit before settlement begins. A settlement transaction handles exactly one claimed item. It must never wait or poll for another item.
5. Preserve lock order: Order → Attempt → Voucher → Products ascending → Courses ascending → ledger → final work fence. New participants may not acquire these resources in another order.
6. The database must support the tested MySQL locking semantics, `FOR UPDATE SKIP LOCKED`, microsecond UTC timestamps, V9 CHECK constraints, and the tested isolation behavior. Database UTC is authoritative for lease decisions.
7. The lease must exceed the worst supported settlement transaction duration with operational margin. Pool acquisition, driver socket, statement, MySQL lock-wait, and Spring transaction timeouts must terminate before lease expiry can permit a second owner.
8. The connection pool must reserve enough capacity for configured workers, IPN/Checkout traffic, monitoring, and administrative recovery. Worker concurrency must never be increased merely to consume all pool connections.

## Proposed configuration contract

Use a separate worker flag so enqueue and payment evidence can remain durable while polling is stopped.

```yaml
app:
  payment:
    settlement:
      enabled: false                 # existing core gate; default false
      lease-duration: 120s           # existing candidate/default
      transaction-timeout: 60s       # existing candidate/default
      claim-transaction-timeout: 5s  # existing candidate/default
      statement-timeout: 5s          # existing JDBC claim query timeout
      worker:
        enabled: false               # proposed; must default false
        batch-size: 4                # proposed initial value; allowed 1..64
        poll-interval: 2s            # proposed fixed delay after batch completion
        concurrency: 1               # proposed canary value
        max-attempts: 8              # proposed validation mirror; must equal V9/code
        shutdown-grace: 75s          # proposed; longer than transaction timeout
```

Required rules:

- `worker.enabled` has no implicit relationship to profiles and must be exactly false when absent.
- Worker startup requires both `settlement.enabled=true` and `worker.enabled=true`; either false means no claims.
- `batch-size` is validated in `1..64`. Begin at 1 for canary, then 4. Do not start at 64.
- `poll-interval` is a fixed delay after completion, not fixed rate; optionally add bounded instance jitter to avoid synchronized polling. No overlapping invocation is allowed per worker slot.
- `concurrency` begins at 1 per deployment and has a conservative global cap derived from pool and database capacity. Scaling requires measured lock/deadlock results.
- `max-attempts` is not operationally adjustable today. V9 and code fix it at 8; startup must reject any other configured value until a separately reviewed schema/code change exists.
- Retry timing remains the implemented full-jitter policy: 0–5 seconds after attempt 1, exponentially increasing to a 15-minute cap. Operators must not edit `available_at` directly.
- Lease 120 seconds and transaction timeout 60 seconds are initial candidates, not production approval. Startup validation should require lease ≥ transaction timeout + shutdown/lock safety margin agreed from production latency data.
- On shutdown, stop claiming first, allow the current item to finish for the configured grace, and do not forcibly mark it retryable. If termination wins, transaction rollback and lease expiry provide recovery.
- Worker IDs must be non-secret, unique per process/slot, stable for the process lifetime, valid under the existing 64-character ASCII contract, and present in structured logs.

## Startup validation and fail-closed conditions

The future worker wrapper must refuse to start claiming if any condition fails:

- Either activation flag is absent/false, or configuration validation fails.
- V9 is absent, Flyway validation is not successful, or schema constraints/indexes do not match the application artifact.
- Database product/version or locking/isolation behavior is unsupported.
- Course, Product, Voucher, ledger, work repository, UTC clock, datasource, or transaction manager dependency is absent or ambiguous.
- Any participant cannot prove it joins the caller transaction or performs a remote/non-atomic side effect.
- JPA and JDBC resolve to different datasource identities or transaction resources.
- Timeout ordering/margin is invalid, or deployment pool/driver/database timeouts are unknown.
- Pool capacity is below the approved worker-plus-foreground reserve.
- Required metrics, alert routing, structured logging, and runbook ownership are unavailable.
- The authorized recovery service is unavailable before a canary that could create MANUAL_REVIEW work.

Startup validation must report a non-secret reason and leave the application serving unrelated functions without a worker, unless the deployment explicitly defines worker readiness as pod readiness. It must never silently substitute no-op business participants.

## Monitoring and alerting

Metrics must be low-cardinality. Work item IDs, user IDs, tokens, payment references, and lease tokens belong in restricted structured logs/traces, not metric labels.

| Signal | Required measurement | Initial alert contract |
| --- | --- | --- |
| READY backlog | Count and oldest `available_at` age | Warn on sustained growth/oldest age above business SLO; page when payment fulfillment SLO is breached. |
| Overdue RETRY_WAIT | Count where `available_at <= database UTC now`, plus oldest overdue age | Warn if nonzero across multiple polls; page on sustained growth. |
| Expired LEASED | Count where `lease_until <= database UTC now` | Warn immediately if persistent beyond two polls; page if reclaim does not reduce it. |
| MANUAL_REVIEW | Count by allowed failure code and oldest age | Ticket immediately; page for settlement conflict, evidence inconsistency, or SLO breach. |
| Settlement latency | Event received/enqueued to work COMPLETED, histogram and percentiles | Alert against the agreed fulfillment SLO, separately for first-attempt and retried work. |
| Retry exhaustion | Counter for transition to MANUAL_REVIEW at attempt 8 | Alert on every occurrence and on rate spikes. |
| Ambiguous outcome | Counter for orchestration UNKNOWN by observation (`COMMIT_OUTCOME_UNKNOWN`, execution exception, evidence mismatch, retry-transition unknown) | Alert on every occurrence; page on repeated items/rate spike. UNKNOWN is not a database state. |
| Batch health | Claimed/completed/retry/review/unknown, batch duration, empty polls | Alert on unknown/review ratios and zero throughput with eligible backlog. |
| Transaction health | Deadlocks, lock timeouts, connection acquisition, statement/socket timeout, rollback rate | Trend baseline; page when correlated with backlog or ambiguous outcomes. |
| Lease safety | Settlement duration versus remaining lease at completion | Warn before p99 approaches the safety margin. |

Dashboards must correlate work state, event, Order, attempt, settlement ledger, retry generation, attempt count, worker ID, and sanitized outcome. Logs must never expose VNPay secrets, full callback data, lease tokens, or unnecessary personal data.

## Manual recovery policy

### UNKNOWN orchestration outcome

UNKNOWN is an observation only. It must not be written as a new V9 state and must not trigger a direct retry or state edit.

1. Capture the work/event/Order correlation and observation code; never log the lease token publicly.
2. Read fresh committed evidence in a new transaction: work state/token generation, settlement ledger identity, Order and attempt states, Voucher state, Course claims, and Product reservations.
3. If all settlement evidence is consistent and work is COMPLETED, close the incident without mutation.
4. If work remains LEASED, do not clear or requeue it. Allow the current database-time lease to expire. The existing claim path may reclaim it; replay must verify durable evidence before any participant action.
5. If evidence is inconsistent, disable new worker claims and route the item to controlled investigation. Do not infer success from an exception or an in-memory result.
6. Repeated lease expiry or exhausted attempts must end in MANUAL_REVIEW, never automatic CLOSED_NOT_ELIGIBLE.

### MANUAL_REVIEW

No direct SQL update is permitted. Before activation, implement an authenticated service that, in one local transaction:

1. Authorizes a least-privilege operator and records the actor user ID.
2. Locks the work item and reads all current settlement/participant evidence.
3. Requires current state MANUAL_REVIEW and validates prior attempt/retry generation.
4. Uses a caller-supplied canonical UUID action key for idempotency.
5. Appends exactly one V9 action row and conditionally changes the work state.
6. For REQUEUE, accepts only `TRANSIENT_CAUSE_RESOLVED`, `ADAPTER_RESTORED`, or `EVIDENCE_REVERIFIED`, increments retry generation, resets the approved attempt budget according to a separately tested contract, and sets READY using database UTC time.
7. For CLOSE_NOT_ELIGIBLE, accepts only `REVIEWED_NOT_ELIGIBLE` after fresh authoritative evidence proves settlement must not occur.
8. Commits the audit action and state transition atomically; a stale state/generation changes nothing.

Operators may never create a settlement ledger row, consume a Voucher, confirm a claim/reservation, change an Order, clear a lease, or mark COMPLETED manually. Recovery authority and worker database credentials must be separated and audited.

## Security and integration blockers

- Course, Product, and Voucher production adapters are absent. Their owning members must supply atomic, idempotent, lock-order-compatible implementations and concurrency tests.
- There is no worker-specific configuration binding, conditional bean, bounded poller, shutdown lifecycle, or global concurrency control.
- There is no secured operator recovery service, RBAC policy, action-key API, audit query, or approval/runbook workflow.
- Timeout candidates for pool acquisition, connection establishment, MySQL lock wait, and socket behavior are not fully wired; shared datasource changes require whole-application capacity testing.
- Monitoring, alerting, sanitized structured logs, and production SLO thresholds are not implemented.
- Production schema deployment and artifact provenance must confirm V9 is included and immutable. The current audit does not access a production database.
- The settlement core is also conditional on VNPay enablement. Startup must validate the intended payment-provider combination rather than accidentally creating only part of the dependency graph.
- Database credentials must have only required DML/locking privileges at runtime; migration and operator privileges must not be granted to the worker identity.

## Activation prerequisites

Production activation requires recorded approval of all items below:

1. Course, Product, and Voucher adapters implemented and verified under the same MySQL transaction manager.
2. Guarded integration tests expanded to use the real production adapters and passing under concurrent worker, IPN, Checkout, and expiration contention.
3. Worker configuration/lifecycle implementation reviewed, default-disabled, and proven not to overlap per slot.
4. Operator recovery service implemented with V9 audit, fencing, idempotency, RBAC, and security tests.
5. V9 deployed and Flyway validation successful in a production-like environment.
6. Pool, driver, Spring transaction, MySQL lock, statement, socket, lease, and shutdown budgets load-tested and approved.
7. Monitoring dashboard, alerts, sanitized logs, on-call ownership, and UNKNOWN/MANUAL_REVIEW runbooks exercised.
8. Backup/restore and disaster-recovery exercises demonstrate that work, action audit, ledger, Order, Voucher, Product, and Course evidence remain mutually consistent.
9. A canary-specific worker identity, concurrency cap, kill switch, and rollback owner are assigned.
10. Security review confirms least privilege, no secret leakage, and no public recovery or worker endpoint.

## Staged rollout

1. **Stage 0 — dark deployment:** Deploy code and V9 with both flags false. Expose read-only metrics and validate schema/dependency/timeout checks. No claims.
2. **Stage 1 — shadow operations:** Keep the worker false. Measure READY/retry/manual backlog queries and rehearse recovery against non-production fixtures. Confirm alerts and on-call routing.
3. **Stage 2 — single canary:** Enable one instance, concurrency 1, batch size 1, conservative poll interval. Observe every completed item, participant evidence, lease margin, deadlock/timeout rate, and UNKNOWN count.
4. **Stage 3 — bounded canary:** Increase batch size to 4 while retaining concurrency 1. Hold through a representative traffic and retry window. Any UNKNOWN, evidence mismatch, unexplained MANUAL_REVIEW, or lease-margin breach stops promotion.
5. **Stage 4 — limited scale:** Add one worker slot at a time only after database/pool review. Do not increase batch size and concurrency simultaneously.
6. **Stage 5 — steady state:** Retain explicit caps and the worker kill switch. Revalidate after schema, adapter, datasource, timeout, or payment-provider changes.

## Rollback procedure

1. Set only `worker.enabled=false` through the approved deployment/configuration mechanism. This stops new claims while durable enqueue continues.
2. Stop polling first and allow the active item to finish within shutdown grace. Do not interrupt a transaction merely to accelerate rollback.
3. If a process terminates, do not edit its lease. Monitor until database-time expiry permits fenced reclaim.
4. Verify no new claims, record READY/RETRY_WAIT/LEASED/MANUAL_REVIEW counts, and reconcile any UNKNOWN observations from fresh committed evidence.
5. Keep V9 and durable work data. Do not downgrade/drop the schema or delete queued work as rollback.
6. If participant integrity is suspected, keep the worker disabled, preserve evidence, and use only the audited operator workflow after root-cause review.
7. Re-enable from Stage 2, not at the prior scale, after the defect and backlog plan are approved.

## Final decision

The settlement foundation is suitable for completing production integration work, but it is **not safe to activate in production today**. The next implementation phase should deliver the three local transaction participants and their real-adapter concurrency tests, followed by a separately reviewed default-disabled worker lifecycle/metrics package and an audited operator recovery service. Until then, all settlement worker activation flags must remain false and no scheduler, startup runner, endpoint, or background thread may invoke the orchestrator.
