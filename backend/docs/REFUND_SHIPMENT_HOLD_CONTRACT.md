# Product Refund Shipment HOLD Contract

**Status:** Proposed D/E integration contract only. The repository currently has no integrated Shipment, ShipmentItem, Product, Inventory reservation, packing-plan, carrier-handover, or Refund persistence implementation. Names and DTOs below are proposals, not existing APIs. No migration, adapter, endpoint, or provider behavior is implemented here.

## Verified repository facts

- `OrderItemJpaEntity` stores immutable `item_type`, `item_id`, `quantity`, `name_snapshot`, `unit_price_vnd`, `gross_amount_vnd`, `allocated_discount_vnd`, and `net_amount_vnd`.
- `OrderItem.netAmountForUnits(firstUnit, unitCount)` divides the immutable net line amount into deterministic contiguous zero-based unit ranges. The caller must prevent unit-range reuse.
- `PaymentSettlementJpaEntity` records one immutable committed settlement per Order, attempt, and payment event. Financial refund must reference it; it is not mutable refund state.
- Order shipping fee and the immutable `order_shipping_addresses` snapshot exist. No current code defines a shipping-fee refund for full Shipment cancellation.
- Search of current production Java and V1–V8 migrations found no Member E shipment/inventory implementation or refund persistence. `InventoryReservationPort` and confirmation/release ports are interfaces only.
- The application declares `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`; Spring Boot configures the JPA datasource and transaction manager. Whether Member E's future code uses this same physical datasource/manager is unverified.

## Proposed typed boundary

Example Java API shape for discussion. E should adapt its actual Shipment and inventory model rather than create duplicate D-side Shipment/stock tables.

```java
interface ShipmentRefundHoldPort {
    HoldResult acquireHoldForRefund(AcquireShipmentHold command);
    FulfillmentChangeResult applyApprovedProductQuantityChange(ApproveShipmentRefund command);
    HoldResult releaseHoldAfterRejection(RejectShipmentRefund command);
    HandoverEvidence getShipmentHandoverEvidence(Integer orderId, Integer shipmentId);
}

record AcquireShipmentHold(
    Integer authenticatedUserId,
    Integer orderId,
    Integer shipmentId,
    Integer refundRequestId,
    List<OrderItemUnitRange> requestedItems,
    String idempotencyKey,
    Instant observedAt) {}

record OrderItemUnitRange(Integer orderItemId, int firstUnit, int unitCount) {}

record ApproveShipmentRefund(
    Integer adminUserId,
    Integer orderId,
    Integer shipmentId,
    Integer refundRequestId,
    List<OrderItemUnitRange> approvedItems,
    String decisionId,
    Instant decidedAt) {}

record RejectShipmentRefund(
    Integer adminUserId, Integer orderId, Integer shipmentId,
    Integer refundRequestId, String decisionId, Instant decidedAt) {}

record HoldResult(HoldOutcome outcome, String holdReference,
                  List<OrderItemUnitRange> heldItems, Instant acquiredAt) {}
enum HoldOutcome { ACQUIRED, ALREADY_HELD_FOR_REQUEST, HANDED_OVER, NOT_FOUND,
                   NOT_OWNER, ITEMS_UNAVAILABLE, CONFLICT, REQUIRES_REVIEW }

record FulfillmentChangeResult(ChangeOutcome outcome, String changeReference,
                               List<OrderItemUnitRange> appliedItems, Instant appliedAt) {}
enum ChangeOutcome { APPLIED, ALREADY_APPLIED, CONFLICT, REQUIRES_REVIEW }

record HandoverEvidence(Integer orderId, Integer shipmentId, boolean committed,
                        Instant committedAt, String carrierReference,
                        String evidenceVersion) {}
```

The data model may be different in E's implementation. If E's domain does not expose individual physical unit identity, the API may use quantities per OrderItem instead of unit ranges, but it must reserve non-overlapping quantities atomically and preserve deterministic value allocation. Return bounded stable references, not carrier secrets or credentials.

### Semantics and ownership

- D authenticates the customer and verifies that `authenticatedUserId` owns the Order. E must independently scope the requested Shipment to that Order; do not trust a client Shipment ID or OrderItem ID.
- `orderItemId` must belong to `orderId`, be `PRODUCT`, and have enough unrequested/unapproved quantity remaining. COURSE lines are never part of a Shipment HOLD request.
- E owns Shipment state, handover evidence, packing plan, and stock/reservation effects. D owns refund request state and financial refund records. Neither module should duplicate the other's authoritative tables.
- `acquireHoldForRefund` must atomically test that handover is not committed, reserve the requested units/quantity against duplicate claims, and register this request as a HOLD owner. Repeating the same key/request returns the same hold result. Reusing a key with different Shipment/items conflicts.
- `applyApprovedProductQuantityChange` must be idempotent by `refundRequestId`/`decisionId`; atomically remove approved units from the shipment packing plan and apply E's approved inventory disposition. The disposition could be return-to-available, damaged/quarantine, or another E-owned state; do not assume physical stock is sellable again.
- `releaseHoldAfterRejection` releases only the rejected request's quantity reservation and ownership reference. The shipment-level hold is cleared only if no other unresolved request still owns a hold and E confirms handover remains uncommitted.
- Handover evidence must distinguish committed handover from a mutable READY/PACKING label. A boolean/status read alone is insufficient unless the handover command serializes on the same Shipment row/aggregate lock.

## Transaction, isolation, and lock contract

For request acceptance, D's refund request insert and E's hold acquisition must be one local REQUIRED transaction using the same datasource, transaction manager, and database. If E cannot join that transaction, this atomic acceptance guarantee is unavailable; stop integration and design a durable reservation/saga boundary separately.

For Admin approval, the decision, idempotent E packing/inventory adjustment, and refund obligation update must commit together locally. Do not hold the transaction open during VNPay refund calls. Provider execution happens later outside the transaction against a persisted approved obligation, then verified outcome is recorded in another short transaction.

Recommended lock order to review jointly with E:

1. Order row.
2. PaymentAttempt rows by ascending ID, then the immutable settlement row, for a financial refund path that must serialize with settlement/expiration.
3. Shipment row/aggregate, which serializes HOLD acquisition with handover.
4. Shipment item/packing-plan rows ordered by stable item key.
5. OrderItem, D refund request/item, and unit-allocation rows in ascending IDs/range start.
6. Inventory reservation/stock rows in ascending Product ID.
7. Refund provider attempt/outcome row.

This is a candidate order, not proven compatible with E's unseen locking path and not an assertion about E's existing implementation. The relative Shipment -> shipment item -> OrderItem/refund -> Inventory order is retained in the D persistence proposal. E must disclose its actual handover and inventory lock order and identify any cycle before implementation. If handover does not lock Order, it must still contend on the same Shipment lock held by refund acceptance; no path may decide eligibility from a stale unlocked Shipment read. For non-financial E handover itself, the E-side serialization begins at Shipment and follows E-owned shipment/packing/inventory rows; D payment locks are not implied to exist in E's operation.

### Handover cutoff

The boundary is the instant E atomically commits carrier handover. Refund acceptance and handover race on the same Shipment serialization point:

- If HOLD commits first, handover must fail/retry while any unresolved HOLD owner exists.
- If handover commits first, a new Product refund request is rejected, even if delivery has not occurred.
- Equality/order of timestamps alone does not decide the race. The committed serialized state transition is authoritative. Persist UTC `DATETIME(6)` event time for audit; do not infer transaction ordering from app-server clocks.

## Multiple requests and mixed Orders

- Multiple unresolved requests may hold one Shipment. Track hold ownership per request (or E's equivalent), not just a single boolean that one rejection can clear.
- Quantities/unit ranges across active requests must be disjoint. Enforce this atomically; read-before-write overlap checks alone are not safe under concurrency.
- A request can cover multiple Product OrderItems in one Shipment only if E confirms those items share one fulfillment adjustment transaction. If they span Shipments, either create separate request units per Shipment or define a transaction-safe multi-Shipment aggregate; do not imply cross-database atomicity.
- An Order with multiple Shipments must track the Shipment affected by each requested Product line. A hold on one shipment must not block unrelated shipment handover unless E's packing model requires it.
- A mixed COURSE + PRODUCT Order may have a Course refund after Product handover. Course eligibility is checked through C evidence and does not acquire Product Shipment HOLD. Product request rules remain pre-handover only.
- If an Order is unpaid/unsettled, cancel/adjust the Order and release relevant reservations under the unpaid lifecycle. Do not create a financial refund or provider refund attempt without a committed settlement.

## Race-condition analysis

All local rows in a scenario must use E's real transactional model. Fail closed on missing Shipment/evidence, lock timeout, unknown outcome, or any mismatch; preserve an auditable retry/reconciliation state rather than assuming acceptance.

| Scenario | Expected behavior | Required boundary / idempotency | Fail closed? |
|---|---|---|---|
| 1. Refund request vs carrier handover | Exactly one wins the Shipment serialization point. HOLD first blocks handover; handover first rejects new request. | Same Shipment lock; atomic request + hold transaction. Retry same request key returns prior outcome. | Yes, on uncertain lock/commit result. |
| 2. Two requests target same Product units | One reserves them; the other conflicts or receives only an explicitly allowed disjoint remainder. No overlap. | Shipment then unit/quantity allocation lock; unique/atomic active allocation keyed by OrderItem unit range or equivalent. | Yes. |
| 3. Two requests target different units in one Shipment | Both may be accepted; both retain HOLD ownership. Neither may clear the other's hold. | Same Shipment serialized update; independent disjoint allocations; ownership count/reference tracked per request. | Yes if quantity/packing plan cannot be reconciled. |
| 4. Admin approval vs handover | Existing HOLD blocks handover while E updates packing/inventory. Approval commits only if fulfillment adjustment is atomic. | Shipment lock then packing/item then inventory locks; idempotent decision ID. | Yes; if handover already committed before any hold, approval must reject/review. |
| 5. Admin rejection while another request still holds | Release only this request's claim; keep Shipment held for all other unresolved owners. | Shipment lock; idempotent request-specific release; derive hold state from unresolved owners. | Yes if ownership records disagree. |
| 6. Quantity update fails halfway | No partial packing, inventory, or refund decision commits. | Same local transaction; all updates rollback. If E's model spans services/databases, do not claim atomicity—design a durable saga. | Yes. |
| 7. Transaction rollback after HOLD acquisition | Refund request and hold/quantity reservation both roll back. If commit outcome is unknown, query by idempotency key before retry. | Same transaction manager; durable idempotency key; no REQUIRES_NEW. | Yes until outcome is resolved. |
| 8. Same idempotency key repeated | Same canonical request returns the original request/hold result; changed Shipment or quantity conflicts. | Unique `(owner, idempotency_key)` on D request; fingerprint includes Order, Shipment, OrderItem/ranges, reason. E uses same operation reference. | Yes on key conflict. |
| 9. Admin approval/rejection repeated | Same decision ID is a no-op returning prior result; opposite later decision is rejected or explicitly escalated. | Lock request then Shipment in agreed global order; unique decision/effect reference. | Yes on conflicting decision. |
| 10. Provider refund timeout after fulfillment adjustment | Physical plan may already be durably changed; keep financial obligation PROCESSING/UNKNOWN and query/reconcile same provider reference. Do not create independent refund or restore packing automatically. | Provider call outside DB transaction; stable provider idempotency key; retry/query same attempt. | Yes; no second refund. |
| 11. Mixed COURSE + PRODUCT Order | Product request holds only its Shipment and listed Product quantities. Course request follows C policy independently. | Separate per-domain evidence/transaction contracts; shared Order lock if both mutate refund allocation, then resource locks in agreed order. | Yes for unavailable evidence on the requested item; do not block unrelated eligible item solely due to other type. |
| 12. Handover event arrives late or duplicated | E's committed handover record is authoritative. Duplicate event is idempotent; late-arriving event after refund acceptance triggers reconciliation if ordering cannot be proven, never silent auto-release. | Stable carrier event identity; dedupe and compare provider event time with internal committed transition; preserve both timestamps and receipt time. | Yes when event ordering/evidence is ambiguous. |

## Decisions

### Confirmed team policy

- Product refund request is per PRODUCT OrderItem and can be partial quantity.
- Request acceptance cutoff is committed carrier handover, not customer delivery/receipt.
- Accepted request atomically holds the corresponding Shipment against handover.
- Shipment status and hold are separate; READY may remain READY while held.
- Admin decision governs hold resolution. Rejection releases only its own quantity/hold when no other unresolved request needs it.
- Approval atomically updates physical packing/fulfillment plan and E-owned inventory state; release HOLD only after consistency is durable.
- Provider refund completion is separate from physical plan consistency.
- Do not refund shipping automatically for partial Product refunds; do not create a financial refund for an unsettled Order.
- Course refund is separate and remains possible after Product handover if C eligibility rules pass.

### Recommended choices, subject to E review

- Use one D refund request idempotency key plus a canonical fingerprint; propagate a stable request/effect ID to E.
- Prefer request-owned HOLD references/counts over a boolean hold flag.
- Serialize handover and HOLD acquisition on one Shipment aggregate lock; use persisted committed transition/evidence rather than wall-clock comparison as the race winner.
- Reserve disjoint Product unit ranges or an equivalent quantity allocation under a database constraint/lock.
- Use the shared Order -> Shipment -> Shipment items -> Refund allocation -> Inventory lock order only if it matches E's existing flows.
- Treat missing Shipment, missing handover evidence, stale evidence, and ambiguous carrier events as unavailable/requires review.

### Decisions for Member E

- What are the actual Shipment, ShipmentItem, inventory reservation, and packing-plan entities/tables and statuses?
- What operation commits carrier handover, what lock does it take, and can it enforce active refund holds in that same transaction?
- Does each Order map to one Shipment or can a Product OrderItem be split across several Shipments?
- How are physical unit identities represented? If only quantities exist, how can two partial requests reserve disjoint units?
- What exactly does E update on approval: pick/pack rows, reservation quantity, stock, or all of these? When can product stock safely return to available state?
- Can D's request, E's hold, packing changes, and inventory changes use the same local database, datasource, and REQUIRED transaction manager?
- What lock order and unique constraints already protect handover, reservations, hold ownership, and repeated effects?
- Which carrier handover events are authoritative, how are duplicates keyed, and how are delayed/out-of-order webhooks reconciled?
- Is READY always physically editable? Can a Shipment include items already packed but not handed over?
- How is a shipment hold represented and released when multiple requests overlap or one transaction's outcome is unknown?

### Decisions for Member C

- What are the authoritative Course progress and assignment-submission records and their statuses?
- What timestamp begins the Course seven-day refund period: provider payment, local settlement, or another persisted timestamp? What is the exact boundary?
- At request or approval time should progress/assignment eligibility be sampled, and how is evidence frozen/audited?

### Financial/provider decisions for later approval

- Does full physical shipment cancellation return shipping fee? This remains open; partial refunds exclude it.
- Which VNPay refund capabilities, idempotency keys, query/reference lookup, amount limits, and definitive/unknown outcomes exist?
- Who approves retries after failed or unknown provider outcome? Admin, automated retry, or manual reconciliation?
- What customer-visible refund status should represent physical hold/packing completion separately from financial PROCESSING/SUCCEEDED?
- What audit retention and access policy applies to provider evidence?

## Suggested next phases and prerequisites

### D-Refund-2B — Pure policy and amount tests

Pure `RefundAmountCalculator` and `RefundEligibilityPolicy` functions and database-free unit tests are now implemented. The functions take caller-provided Course window origin/boundary and advisory handover/settlement facts. They do not prove concurrency, acquire a Shipment HOLD, or reserve unit ranges. Production use remains blocked until Member C/E evidence and persistence contracts exist.

### D-Refund-3 — Persistence proposal and concurrency plan

Design request/item/provider-attempt persistence only after idempotency, multiple-item partial outcomes, unit-range ownership, and shipping-fee policy are settled. Include guarded concurrency scenarios for remaining amount and unit allocation. Do not create a migration until reviewed.

### D-Refund-4 — Real Member E Shipment HOLD integration

Blocked until E supplies actual schema/service, handover serialization point, hold ownership semantics, packing/inventory adjustment behavior, and same-datasource transaction proof. Integrate against E-owned models only; test request-vs-handover and overlapping request contention with real persistence.

### D-Refund-5 — Admin review and provider execution

Blocked on Admin authorization/audit policy, E's durable fulfillment result, VNPay refund capability/idempotency and retry decisions, and a decision about durable outbox/reconciliation. Keep network calls outside long transactions; enable no endpoint before security and failure handling are reviewed.

## Current blocker

No Member E Shipment/HOLD/Inventory implementation is integrated in this repository. The proposed contract and race behavior require E confirmation and actual schema/transaction details before any adapter, migration, or API can be safely implemented.

## D-Refund-2B implementation boundary

The pure policy classes consume values supplied by their caller. They do not query Shipment state, assert that evidence is authoritative, reserve or release a hold, mutate packing/inventory, or create a financial obligation. Their unit tests cannot establish the mutual exclusion required between refund acceptance and carrier handover. That guarantee must be tested against E's real persistence and handover operation after E confirms the shared transaction/lock contract.
