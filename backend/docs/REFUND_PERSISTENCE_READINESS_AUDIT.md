# Refund Persistence Readiness Audit

**Status:** Source-based design audit only. No JPA persistence or migration is approved by this document.

## Repository state and inspected sources

- Branch: `feature/member-d-commerce`
- HEAD: `447a3c0c3723ca67d36a98452c5fd449679b850c`
- Worktree: two commits ahead of its remote; refund design documents and pure policy/state-machine sources remain untracked. They were preserved.
- Reviewed `RefundStateMachine`, its JUnit tests, `RefundAmountCalculator`, `RefundEligibilityPolicy`, the three refund design documents, V3 order DDL, V8 settlement/reconciliation DDL, and Order/OrderItem/PaymentAttempt/PaymentEvent/PaymentSettlement mappings.

The state machine is pure and immutable, but it is not a persistence adapter. It trusts typed evidence supplied by its caller and retains replay identities only in an in-memory map. D-Refund-3B2.1 adds typed operation and request/item scope to each event identity; this does not make replay durable.

## A. State-to-column mapping

| State-machine field | Proposed durable representation | Nullability / restart reconstruction | Gaps and JPA considerations |
|---|---|---|---|
| `itemType` | `refund_items.item_type_snapshot` | NOT NULL; reconstructable from immutable OrderItem snapshot and copied value | String enum mapping is straightforward. Transaction must verify copied type matches `order_items.item_type`. |
| `decision` | `refund_requests.decision_status`; reviewer, time, decision code plus `refund_events` | Status NOT NULL; review fields nullable only while REQUESTED. Reconstructable from the request row. | Separate request/item rows mean DB CHECK cannot prove item approval fields match request decision. Validate both under the request lock. |
| `fulfillment` | `refund_items.fulfillment_status` | NOT NULL; reconstructable as a D projection | String enum mapping is straightforward. E remains authoritative for packing/inventory state. Current state machine has no transition that enters `REQUIRES_REVIEW`. |
| `hold` | `refund_items.shipment_hold_status` and historical `shipment_hold_reference` | Status NOT NULL; product references required by the route CHECK. The projection is reconstructable, but authoritative ownership is only in E. | Never decide handover/active hold from D’s projection after restart; reconcile with E. Current state machine has no transition to `REQUIRES_REVIEW`. |
| `financial` | `refund_items.financial_status`; detail in `refund_provider_attempts` | NOT NULL; reconstructable only when item and attempt updates/events commit together | The original DDL enum CHECK allowed READY/PROCESSING/SUCCEEDED without approval or a successful attempt. A row-local financial-shape CHECK was added to the proposal; request decision and provider-attempt consistency still require transaction checks. |
| `settlementCommitted` | Existence of `payment_settlements` for the request’s `order_id`; `refund_requests.order_id` FK to unique settlement `order_id` | No boolean column; FK makes a committed settlement mandatory and the settlement is immutable | Reconstruct by settlement lookup. V8 unique Order key makes the route one-to-one. |
| `requestedAmountVnd` | `refund_items.requested_amount_vnd`, plus immutable OrderItem amount/discount snapshots | NOT NULL; reconstructable | Current DDL requires amount > 0 while the state machine accepts zero. Financial-refund persistence cannot store the zero-net physical-only path; keep that path outside these tables until designed. Amount must be recomputed from snapshots and unit positions under lock. |
| `courseEligibilityConfirmed` | No direct column. Could be inferred from an APPROVED Course decision only if every writer enforces the policy. `refund_events` has optional `evidence_sha256`. | Boolean is inferable from a guarded decision, but the evidence facts/reference cannot be reconstructed from the current proposal. | C must provide a stable evidence reference/snapshot contract. Preserve evidence reference and digest on the approval event; do not treat the boolean alone as audit evidence. |
| `reviewRequired` / review reason | Proposed `refund_items.review_required` and `review_reason_code`; pure state now derives the flag from a set of causes. | Current single pair reconstructs one cause, not multiple simultaneous causes. | Require one-open-cause serialization or separately modeled review cases; never map Shipment/HOLD review solely to financial `REQUIRES_REVIEW`. |
| `appliedEvents` | `refund_events.operation_type`, stable `event_key`, `event_fingerprint`, and request/item/optional attempt FKs | Pure map is memory-only; persistence reconstructs replay only when event and state writes commit atomically. | Unique identity is `(request,item,operation,event_key)`; service must lock/read and compare fingerprint to distinguish replay from conflict. Include provider-attempt scope and canonical facts in the fingerprint. |

### Event identity and replay

`RefundStateMachine.EventIdentity` stores a typed operation, request/item scope, bounded stable event ID, and 64-hex payload digest. The pure replay map compares `(operation, scope, stable ID)` and digest. Production callers must derive a versioned fingerprint over typed operation and stable canonical fields. Do not use a raw provider callback map or assume a provider event ID exists.

For provider outcomes, derive a stable local identity from the refund attempt ID, provider, provider refund reference when present, normalized outcome/response code, amount/currency, provider timestamp when valid, and canonical verified payload digest. Exclude local receipt time and verification-run IDs. If a stable provider reference is absent, identical canonical verified facts can be fingerprinted locally; distinct/conflicting facts must be preserved as separate review events. The VNPay refund-side identity/query guarantees remain unverified.

The V5 payment path provides a useful pattern, not proof for refunds: `PaymentEvent.fingerprint()` hashes versioned canonical provider facts and excludes receipt/verification-run IDs; `PaymentEventPolicy` compares the stable provider event reference and fingerprint. No refund callback contract currently guarantees an equivalent provider event ID.

Request idempotency is separate from transition-event replay. Proposed `(user_id,idempotency_key)` uniqueness plus `request_fingerprint` supports canonical request comparison only when the service locks/reads the winning request after a duplicate-key race. The pure state machine does not accept or retain this request key/fingerprint.

## B. `reviewRequired` recommendation

This flag is not a simple derived alias. The corrected pure model derives it from typed review causes: provider UNKNOWN opens a provider-uncertainty cause; definitive failure after adjusted Product fulfillment opens a separate physical/financial cause. Reconciliation removes only provider uncertainty. Keep provider-attempt FAILED/UNKNOWN outcomes intact. Persistence must either enforce one unresolved cause per item or represent multiple causes individually; a single boolean/reason pair is insufficient for concurrent distinct causes.

The pure state machine declares `REQUIRES_REVIEW` values for financial, hold, and fulfillment but exposes no transition that enters or resolves those values. DDL currently permits them. Before entities/services, either add explicit transitions and evidence requirements or reserve/disallow those enum values. Provider UNKNOWN review and reconciliation are now explicit, but this does not resolve Shipment/HOLD review. This remains a domain-to-schema completeness blocker, not a reason to invent E behavior.

## C. Transaction boundaries

| Operation | Atomic local writes | External boundary / rule |
|---|---|---|
| Create request | Lock Order/settlement and OrderItem; insert request, item snapshots, unit reservations, REQUESTED event and request fingerprint | Product E HOLD acquisition must join the same REQUIRED datasource/transaction. If it cannot, atomic acceptance is unavailable; stop for a separately designed durable saga. |
| Admin approval | Lock request/item and unit slots; persist all-or-reject decision, approved snapshots, evidence reference/digest, fulfillment state and event | E packing/inventory quantity adjustment must join the same REQUIRED transaction. No external provider call here. |
| Admin rejection | Lock request/item/slots; decision, financial release (only when safe), HOLD projection and event | E releases only this request’s HOLD ownership in the same transaction. Do not infer the shipment aggregate is unheld while other owners remain. |
| HOLD release after adjustment | Persist D projection and event with E’s idempotent request-specific HOLD release | E remains authoritative; adjustment stays ADJUSTED. This does not mark money refunded. |
| Create provider attempt | Lock item/obligation; change READY to PROCESSING; insert attempt and event/fingerprint | Commit before provider network call. Never hold DB locks over VNPay I/O. Locking the item serializes local attempt creation; unique attempt number/idempotency/reference keys alone do not guarantee one active attempt. |
| Record provider outcome | Lock Order/request/item/attempt in the jointly agreed order; verify attempt route, amount/currency and callback; update attempt/item, allocations and event | Signature verification occurs before trusted evidence enters this transaction. No remote call inside it. UNKNOWN retains allocations and same attempt. |
| Resolve review | Lock affected request/item/attempt/slot rows; persist resolution, any safe state changes and RESOLVED event together | E/C evidence or provider query is obtained outside the transaction; revalidate its reference/version before commit. |

Every successful state mutation and its append-only audit event must commit together. Rejected/conflicting callbacks also need a durable event or reconciliation record without overwriting a prior success. Database atomicity with E is not established because no E persistence/transaction manager is integrated.

## D. Schema consistency findings

### Enforced directly by proposed constraints

- Request idempotency key uniqueness per user; request-to-Order owner route through candidate `(orders.id,user_id)`; one request/item association; item-to-OrderItem route through candidate `(order_items.id,order_id)`.
- Mandatory settlement route through unique `payment_settlements.order_id`.
- Unit-slot primary key `(order_item_id,unit_position)` and slot-to-item/order-item route.
- Provider attempt number and provider idempotency/reference uniqueness; request/item route.
- Event-to-request/item/provider-attempt route through composite FKs. Nullable `provider_attempt_id` intentionally bypasses its composite FK for non-provider events.
- Row-local enum, amount arithmetic, and review-reason pairing checks. UTC `DATETIME(6)` is compatible with existing `Instant` converter conventions.

### Not enforced by SQL alone

- Whether the event fingerprint is valid hex or matches the canonical event; whether a duplicate key has the same digest; request fingerprint canonicalization.
- Snapshot equality with the source OrderItem; unit position below source quantity; sum of allocated units/amounts; no duplicate logical units after a state transition; RESERVED/REFUNDED terminal semantics.
- Request decision versus item approval fields; financial status versus request decision and provider attempt status; exactly one active provider attempt; provider amount equals approved amount; a SUCCEEDED item has a successful attempt; immutable history/terminal state protection.
- E hold ownership, handover cutoff, packing adjustments, and E inventory consistency; C course evidence correctness.

These require a locked transaction service and immutable transition API. A CHECK cannot inspect another row. MySQL unique constraints do allow multiple NULL `provider_refund_reference` values. The current unique keys do not implement a partial unique active-attempt constraint; serialize on `refund_items` and optionally add a generated active-slot unique key if the team requires a database backstop.

### Contradictory or impossible combinations currently allowed

- Parent decision APPROVED with null approved quantity/amount, or REQUESTED/REJECTED with populated approvals: the item-level pair CHECK cannot inspect the parent request.
- READY/PROCESSING/UNKNOWN/SUCCEEDED with no approved snapshot, or a financial status with no matching provider attempt/outcome: the original DDL allowed these; the proposal now adds a row-local approval/amount shape CHECK, while service logic must enforce the cross-table/attempt route.
- Product fulfillment RELEASED while its request HOLD projection is ACTIVE/REQUIRES_REVIEW: the original checks allowed this mismatch; the proposal adds a row-local check requiring RELEASED HOLD for RELEASED fulfillment.
- RESERVED/REFUNDED slot on a rejected request, or FREE after successful provider refund: FKs and current-state CHECKs allow this; transition code must prevent it. Database constraints do not protect terminal status against arbitrary UPDATE.
- Zero amount accepted by the pure state machine cannot be stored in the financial Refund DDL (`requested_amount_vnd > 0`). This is intentionally separate non-financial cancellation work, but the boundary must be enforced/documented before persistence.

### Type, FK, and length review

V3 IDs and V8 order/attempt/event IDs are signed `INT`; proposed FK IDs use signed `INT`. V8’s settlement Order key is unique. Proposed composite candidate keys on V3 `orders(id,user_id)` and `order_items(id,order_id)` are needed unless an equivalent key exists; MySQL FK column order must exactly match those keys. Hibernate can map these as explicit composite joins, but metadata validation remains required.

The state-machine maximum `Reason` enum identifier is `FULFILLMENT_ADJUSTMENT_NOT_CONFIRMED` (36 characters), longer than the proposal’s original `refund_events.reason_code VARCHAR(32)`. The proposal now uses 64 characters for event reason and decision codes. `EventIdentity.key` max 128 matches `event_key VARCHAR(128)`; SHA-256 fingerprints are 64 ASCII hex characters. Enum columns use `VARCHAR`/string mapping. `shipment_hold_reference` length 128 and E identifier compatibility are unverified. All proposed instants use `DATETIME(6)` UTC.

Composite FK candidate keys and all CHECK behavior still require actual MySQL 8 validation. H2 passing cannot prove MySQL FK metadata, CHECK, collation, or lock behavior.

## E. Readiness classification

| Class | Issue | Required resolution |
|---|---|---|
| **BLOCKER before V9/JPA** | Typed event replay now requires operation and request/item scope in addition to stable ID and fingerprint. | Proposal adds `operation_type` and a candidate composite unique key; validate mapping and locked same-key fingerprint comparison in persistence tests. |
| **BLOCKER before V9/JPA** | `reviewRequired` crosses financial/physical axes and the pure model tracks multiple causes. | Proposal's single flag/reason pair cannot represent multiple simultaneous causes; enforce a one-cause invariant or add a separately reviewed review-case representation and define resolution authorization. |
| **BLOCKER before V9/JPA** | Current machine’s longest reason exceeds old `reason_code(32)`. | Proposal widens event reason/decision codes to 64; align DTO/entity validation. |
| **BLOCKER before workflow implementation** | Machine exposes three REQUIRES_REVIEW statuses but cannot enter/resolve them; rejected/conflicting events are not retained in `appliedEvents`. | Add explicit transitions or reserve these statuses; persist rejected/conflicting audit events without mutating successful state. |
| **BLOCKER for zero-net flow** | Machine permits settled zero-net item requests; DDL only represents positive financial refunds. | Keep zero-net/untsettled cancellation outside financial refund tables or design a separate non-financial obligation before claiming support. |
| **BLOCKER before production write service** | Cross-row and terminal invariants are application-only; no entities/services exist to enforce them. | Specify aggregate lock order, guarded JPA transitions, transaction boundaries, and terminal allocation/provider rules; add H2 and guarded MySQL tests. |
| **REVIEW — Member E** | Actual Shipment/HOLD owner table, stable operation keys/references, handover serialization, packing/inventory transaction and lock order are absent. | E must confirm same local transaction manager or D/E atomicity must be redesigned. Do not infer E lock order. |
| **REVIEW — Member C** | Course eligibility evidence source/reference, progress/assignment snapshot and seven-day origin/boundary are absent. | C must provide immutable evidence identity and freshness/authorization contract. |
| **REVIEW — Admin/Finance** | Review resolution roles, multiple simultaneous review causes, provider retry/query/idempotency, zero-net cancellation and shipping-charge decisions remain open. | Approve policies before workflow/API activation. |
| **OPTIONAL/defer** | Generated-column/unique backstop for one active provider attempt; dedicated review-case child table if multiple simultaneous cases must be independently open. | Row lock and aggregate review marker can be sufficient for MVP if every writer uses the same transaction boundary. |
| **OPTIONAL/defer** | Full Shipment shipping-fee refund allocation. | Keep excluded until business allocation is approved; never place it in Product unit allocations. |

## F. Exact Member E/C dependencies

**Member E must provide:** authoritative Shipment and Shipment-item identifiers; atomic acquire/release HOLD with stable request/operation idempotency keys; proof that handover locks the same serialization point; idempotent packing/quantity adjustment and inventory effect; behavior when multiple requests hold one Shipment; stable references and event/evidence facts; exact lock order; same-datasource and same-transaction-manager participation or an approved saga boundary.

**Member C must provide:** authoritative per-user Course ownership/eligibility evidence; progress basis and successful-assignment semantics; stable evidence reference/digest and observation time; evidence freshness at approval; approved seven-day origin and boundary; transaction/locking behavior if ownership can change concurrently.

**Admin/Finance must provide:** reviewer authorization and decision identity; resolution of multiple open review causes; provider refund reference/query/retry guarantees; treatment of zero-net physical cancellations and full-shipment shipping charges.

## Verdict and next phase

**V9 is not ready for implementation approval.** The proposal has been corrected for event fingerprints, independent review markers, reason-code width, row-local financial approval shape, and released HOLD consistency. Cross-table/terminal invariants, zero-net scope, evidence semantics, and integration contracts remain unresolved. No migration should be written until the BLOCKER items are closed and E/C provide their contracts. Passing default H2 tests would not validate MySQL constraints or E concurrency.

Recommended next phase: **D-Refund-3B3: Persistence contract closure** — choose how durable storage represents multiple simultaneous review causes, define authorized provider reconciliation evidence and unresolved Shipment/HOLD review resolution, and produce a persistence invariant table for the typed state-machine events. Then obtain E/C contract confirmation before proposing V9/JPA. After implementation, run isolated H2 persistence tests and guarded MySQL tests only against the approved `drawing_course_it` target.

## D-Refund-3B2.1 source correction

This follow-up supersedes the earlier source findings above where they described `markUnknown()` as clearing `reviewRequired` or `EventIdentity` as key-only. The pure state machine now:

- Sets `FinancialStatus.UNKNOWN` and opens `PROVIDER_OUTCOME_UNKNOWN` review for both local timeout and verified UNKNOWN provider evidence. Identical typed identity/fingerprint replay is idempotent; a changed fingerprint under the same identity is rejected without changing review state.
- Keys in-memory replay by operation type, positive request/item scope, and bounded stable event ID, then compares the canonical payload SHA-256. `EventIdentity` therefore separates different operations and obligations. `ProviderEvidenceKind.RECONCILIATION` is required to resolve UNKNOWN; it is a caller assertion and does not verify a signature or provider query itself.
- Tracks review causes independently in a set. Reconciliation removes only the provider-unknown cause; definitive failure after adjusted Product fulfillment records a separate cause. There is no unrestricted clear-review method. This prevents one resolution from erasing a different known cause.
- Keeps successful financial status terminal against a later failure, keeps fulfillment adjustment separate from HOLD release, retains Product physical state after provider failure, and keeps Course transitions independent of Shipment state.

The proposal now adds `refund_events.operation_type`, constrains its known values, and uses `(refund_request_id, refund_item_id, operation_type, event_key)` as the candidate unique identity. Production persistence must lock/read and compare `event_fingerprint`; a unique key cannot distinguish same-identity/same-payload replay from changed-payload conflict by itself. Persist provider-attempt scope in the canonical fingerprint and route it through the event's nullable-but-validated attempt FK.

**Remaining persistence blocker:** the current proposal's single `review_required` plus `review_reason_code` cannot represent multiple simultaneous review causes as faithfully as the pure model's set. Before V9, either approve/implement a one-open-cause-per-item invariant or design a child review-case/event-backed representation. Provider UNKNOWN reconciliation is only a typed pure transition; production must define who may attest reconciliation and how its authoritative evidence is verified. Shipment/HOLD review resolution still requires Admin authorization plus authoritative Member E evidence. Replay data remains in memory in this pure model, so no durable idempotency is claimed.
