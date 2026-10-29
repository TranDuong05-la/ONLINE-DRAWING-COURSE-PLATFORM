# Refund Domain and Policy Proposal

**Status:** Pure policy and amount functions are implemented and unit-tested. Refund persistence, Shipment HOLD adapters, migrations, endpoints, provider calls, and settlement changes remain design-only and are not implemented.

## Verified current foundation

- `OrderItemJpaEntity` persists immutable item type/ID, name, quantity, unit price, gross amount, voucher eligibility, allocated discount, and net amount. The persisted monetary basis is VND (`BIGINT`). `OrderItem.netAmountForUnits(firstUnit, unitCount)` apportions a line's integer net amount deterministically across units; its source comment explicitly leaves refund eligibility and range reuse to a future refund ledger.
- `OrderJpaEntity` stores the immutable Order total, discount, shipping fee, currency, payment deadline, and workflow/payment statuses. Shipping address is a separate immutable Order snapshot.
- `PaymentSettlementJpaEntity` is an immutable successful settlement record, unique by Order, PaymentAttempt, and PaymentEvent. It is not a refund record and must remain unchanged.
- `PaymentEvent` / reconciliation records provide payment evidence and review history. They do not currently record refund requests or provider refund outcomes.
- Voucher discount is allocated across eligible Course/Product lines and is already reflected in each OrderItem's `allocatedDiscountVnd` and `netAmountVnd`. Shipping is outside those item amounts.
- No integrated Course progress/assignment evidence, Product shipment/handover evidence, refund entity/table, or provider refund executor was found. No production Refund endpoint exists.

Relevant source: `entity/OrderItem.java`, `entity/OrderItemJpaEntity.java`, `entity/OrderJpaEntity.java`, `entity/OrderShippingAddressJpaEntity.java`, `entity/PaymentSettlementJpaEntity.java`, `entity/PaymentAttempt.java`, `entity/PaymentReconciliationCaseJpaEntity.java`, and `service/OrderDiscountAllocator.java`.

## Proposed domain

Keep three concepts separate:

1. **Refund request** — the customer/admin decision workflow for one Order and one or more OrderItems. It records requester, reason, status, decision actor/time, and timestamps. Partial Product quantity requests are confirmed policy. Course quantity is one in the current Order item rules.
2. **Refund item** — an immutable requested allocation to a specific OrderItem, with requested quantity/unit range and calculated VND amount snapshot. A refund item refers to the original OrderItem and never reprices from the current catalog.
3. **Provider refund attempt/evidence** — one or more idempotent attempts to return funds against the original successful PaymentAttempt/settlement. It records provider reference, amount, status, request/response evidence digest, and provider timestamps. Retries are attempts for the same approved refund obligation, not new customer requests.

Suggested lifecycle:

```text
REQUESTED -> APPROVED -> PROCESSING -> SUCCEEDED
     |           |            |
     v           v            v
  REJECTED      REJECTED      FAILED -> PROCESSING (admin/system retry policy)
```

- `REQUESTED -> APPROVED` or `REQUESTED -> REJECTED`: authorized Admin decision, with actor, timestamp, and reason recorded.
- `APPROVED -> PROCESSING`: system creates an idempotent provider attempt. Approval snapshots the maximum authorized amount and item allocation.
- `PROCESSING -> SUCCEEDED`: only verified provider refund evidence can complete the financial attempt. This does not alter the immutable payment settlement.
- `PROCESSING -> FAILED`: a definitive provider failure. Whether retry can be automatic or requires Admin approval remains undecided. An unknown/timeout outcome must remain pending/reconciliation-required; it must not be treated as a definitive failure that permits a second refund.
- Terminal request/item outcomes must be immutable; corrections use a new reviewed record or explicit resolution event, not rewriting successful financial history.

The request lifecycle may need a `PARTIALLY_SUCCEEDED` aggregate state if a request has multiple items and provider execution can succeed for only some of them. Prefer one provider refund obligation per request unless provider limits require item-level splits; do not report the whole request as `SUCCEEDED` until all approved item amounts are conclusively refunded.

## Eligibility matrix

| Order item / evidence | Proposed eligibility | Evidence owner / blocker |
|---|---|---|
| Any item on an Order without a committed successful settlement | Ineligible for ordinary refund workflow; route late or ambiguous payment to reconciliation | D payment settlement is authoritative. Never auto-refund late/ambiguous VNPay success. |
| COURSE, within the approved 7-day window, progress at most 20%, no successfully submitted assignment | Eligible for review, subject to remaining refundable balance | C must provide authoritative progress and assignment-submission evidence. Missing/unavailable evidence must not be assumed eligible. |
| COURSE, progress over 20% | Ineligible | C evidence. Define whether progress is a point-in-time snapshot at request or approval. |
| COURSE, any successfully submitted assignment | Ineligible | C must define what statuses count as a successful submission and provide durable evidence. |
| PRODUCT, before committed carrier handover | May request a refund for any remaining whole-unit quantity; acceptance must atomically acquire Shipment HOLD | E must serialize HOLD acquisition with the actual handover operation. Missing Shipment/evidence fails closed. |
| PRODUCT, request accepted and unresolved | Shipment remains held; competing refund requests share HOLD ownership, and requested units cannot be claimed twice | D/E transaction-bound contract; HOLD prevents handover even while Shipment status remains READY. |
| PRODUCT, after committed carrier handover and before delivery | No new Product Refund Request | Handover is the cutoff; customer receipt is not the cutoff. No returns/exchanges are in scope. |
| PRODUCT, after confirmed delivery | No new Product Refund Request under current policy | E shipment evidence remains authoritative; post-delivery returns/exchanges are out of scope. |
| Shipping fee | Not refunded merely because an item is refunded | Order shipping snapshot; a separate shipping refund policy would require approval. |
| Order item with fully refunded or fully reserved amount | Ineligible for additional refund | Refund ledger must serialize and enforce remaining balance. |

The seven-day rule’s start time is unresolved. Candidate timestamps include Order creation, provider payment time, and local settlement time. No implementation should choose among them without policy approval. “Within 7 days” also needs an exact exclusive/inclusive boundary and timezone/precision rule.

## Refund amount proposal

Use the immutable OrderItem `netAmountVnd`, which already reflects that line’s allocated share of the Order-wide voucher discount. Do not use current catalog price, Order gross subtotal, or shipping fee. A refund cannot exceed the line’s remaining net paid amount after successful and pending/processing refund obligations are accounted for.

For a full item refund:

```text
refundable item amount = OrderItem.netAmountVnd - prior successful refunds - active reserved refund amounts
```

For partial Product quantity refunds, use the existing deterministic unit allocation helper (`netAmountForUnits(firstUnit, unitCount)`) so integer VND remainders are assigned consistently. A persistent ledger must prevent the same unit range from being requested/reserved twice. At request time the approved policy requires requested quantity to be whole units; how unit identity maps to packed physical units is an E integration decision.

**Example:** A line has gross 100,000 VND and allocated voucher discount 10,001 VND. Its immutable net amount is 89,999 VND. A full refund is capped at 89,999 VND, not 100,000 VND; the order’s shipping fee is excluded. If quantity is 3 and partial refunds are allowed, the helper allocates 30,000, 30,000, and 29,999 VND to the three unit positions in its defined order.

**Example with prior activity:** If 20,000 VND has been refunded successfully and another 15,000 VND is approved/processing, the remaining amount available for a new refund is 54,999 VND. Failed definitive attempts release their reserved amount only after their outcome is known; timeouts/unknown outcomes retain the reservation pending reconciliation.

## Idempotency and financial limits

- Serialize refund decisions against the Order and relevant OrderItems, with a stable lock order: Order, settlement, OrderItems sorted by ID, refund requests/items sorted by ID, then provider-attempt records.
- Enforce one successful aggregate refund amount ledger per provider refund reference and idempotency key. Prevent duplicate successful refunds against the same OrderItem/unit range.
- Sum successful refunds plus amounts in `APPROVED`/`PROCESSING`/unknown-provider-outcome states under lock; reject any request that would exceed `OrderItem.netAmountVnd`.
- A retry reuses the same refund obligation/idempotency identity. Provider idempotency capability and reference format must be confirmed before automatic retry.
- A callback or API replay must not create another refund obligation or repeat a provider refund. Conflicting provider evidence goes to review and cannot overwrite a successful attempt.
- The settlement ledger remains immutable. Refunds, chargebacks, and financial adjustments are separate records linked to the original Order, settlement, PaymentAttempt, and OrderItem as appropriate.

## Proposed typed ports

These are design contracts only; no adapters are present in the integrated repository.

```java
interface CourseRefundEvidencePort {
    CourseRefundEvidence getEvidence(Integer userId, Integer courseId, Instant observedAt);
}
record CourseRefundEvidence(int progressBasisPoints, boolean hasSuccessfulAssignment,
                            Instant evidenceObservedAt, String evidenceVersion) {}

interface ProductShipmentEvidencePort {
    ShipmentHandoverEvidence getHandoverEvidence(Integer userId, Integer orderId,
                                                Integer shipmentId, Instant observedAt);
}
record ShipmentHandoverEvidence(boolean committed, Instant committedAt,
                                String evidenceReference, String evidenceVersion) {}

interface PaymentProviderRefundPort {
    RefundProviderResult execute(ApprovedRefundSnapshot refund, String idempotencyKey);
    RefundProviderResult query(String providerRefundReference);
}
```

The exact DTO fields and evidence reference retention need B/C/E review. Course evidence must be authoritative and consistent with the authenticated Order owner. Product handover evidence must identify the Order/Shipment and the committed carrier-handover transition; delivery receipt is not the Product refund cutoff. Provider calls must not run while holding a long database transaction: persist the approved obligation and provider attempt first, call the provider outside the transaction, then record verified outcome in a new short transaction. A durable outbox/worker may be needed for crash-safe execution; that is a later schema decision, not assumed here.

Unavailable evidence/provider behavior is conservative: return a typed `EVIDENCE_UNAVAILABLE` / `REQUIRES_REVIEW` outcome, preserve the request state, and do not approve or execute a refund. Never substitute mock eligibility in production.

## Audit and privacy

Record requester, Admin reviewer, decision code/reason, timestamps, item amount snapshots, evidence source/version/reference, provider attempt references, and outcome transitions. Store bounded metadata and payload digests rather than secrets, credentials, card data, or complete signed callback/URL strings. Define retention and access controls with the team. Late/ambiguous payment callbacks remain in the existing reconciliation workflow until an explicit reviewed decision creates a refund obligation.

## Unresolved policy and integration decisions

1. Does the Course 7-day window begin at provider payment time, local settlement time, or Order creation? Is the endpoint inclusive? Use the persisted UTC instant and DATETIME(6) precision consistently.
2. How are logical unit positions mapped to physical packed units/SKUs, and can a shipment contain already-packed but not-yet-handed-over items?
3. May an eligible Course/Product refund be partial in amount, or only a full item/unit amount? If partial amount is allowed, define allocation and remaining-balance rules.
4. Does one request cover multiple OrderItems? If yes, define partial provider success and request aggregate statuses.
5. Which Course progress denominator and assignment states apply? At request time or approval time? What happens if evidence changes while Admin review is pending?
6. Does fully cancelling a physical Shipment refund the shipping fee? Current confirmed policy excludes automatic shipping-fee refunds for partial Product refunds; full-shipment cancellation remains unresolved.
7. Which refund outcomes are definitive failures versus unknown/pending? Which retries are automatic, Admin-approved, or manual? Does the provider support idempotency/query by reference?
8. Are refunds Admin-approved only? Define roles, thresholds, reason codes, and audit visibility.
9. How are refund requests handled when Course/shipment evidence is unavailable or stale? Proposal: deny automatic approval and route to review.
10. Should refunded Orders/items affect Course ownership, inventory, or product return stock? These are separate fulfillment/revocation policies and are not implied by a money refund.
11. How should payment-provider fees, shipping charges, taxes (if introduced), and rounding be treated? Current scope says shipping is not automatically refunded and uses VND whole-unit amounts.
12. What is the legal retention/access policy for refund and provider evidence?

## Recommended implementation sequence

1. Resolve the policy questions above with product, finance, Admin, and B/C/E owners.
2. Obtain real Course progress/assignment and Product handover evidence contracts; confirm same-database ownership and access controls.
3. Design a migration for refund request/item and provider-attempt records with uniqueness, amount caps, audit metadata, and UTC `DATETIME(6)` timestamps. Keep it separate from V8 settlement rows.
4. Pure amount/eligibility policy and database-free unit tests are implemented. Persistence and concurrency tests require a separately approved schema and real D/E adapters.
5. Implement Admin review and provider execution as separate transaction/network boundaries, with durable retries/outbox if required.
6. Add endpoints only after authorization, audit, provider idempotency, and fulfillment/revocation behavior are reviewed.

No migration or implementation is recommended until the unresolved decisions and B/C/E evidence contracts are approved.

## D-Refund-2A confirmed Product policy addendum

- A Product request may cover part of an OrderItem quantity and is accepted only before carrier handover is committed. The cutoff applies even if the customer has not received the parcel.
- Acceptance requires atomic Shipment HOLD acquisition. A Shipment may remain READY while held; the real E handover operation must check and honor the hold.
- Admin rejection releases only this request's quantity reservation/hold ownership. Shared Shipment HOLD is released only when no unresolved request still requires it.
- Admin approval requires E to atomically update the packing/fulfillment plan and relevant inventory state. Do not release HOLD until that physical plan is durably consistent. Financial provider refund completion is separate and is not a prerequisite to release after that consistency point.
- Course refund eligibility remains independent from Product handover. A mixed Order can still have a Course refund request after Product handover, while new Product requests are rejected.
- An unsettled Order does not create a provider refund. Its cancellation/adjustment path is distinct from returning settled money.
- Partial Product refunds do not automatically refund shipping. Whether cancellation of all physical lines refunds shipping remains open.

The detailed proposed D/E contract and race analysis are in `REFUND_SHIPMENT_HOLD_CONTRACT.md`. No Member E Shipment/HOLD implementation was present in the inspected repository; those contracts remain proposals for E approval.

## D-Refund-2B pure implementation

- `RefundAmountCalculator` evaluates PRODUCT unit ranges against immutable `OrderItem.netAmountVnd` using `OrderItem.netAmountForUnits`. It accepts disjoint ranges, rejects invalid/out-of-range/overlapping or already unavailable ranges, and enforces a caller-supplied remaining amount cap. The amount includes the allocated voucher discount and never includes shipping.
- `RefundEligibilityPolicy` returns deterministic decision/reason pairs for Product committed-handover evidence, Course evidence/progress/assignment rules, a caller-supplied Course window start and explicit inclusive/exclusive deadline convention, and committed-settlement presence.
- These functions return advisory results only. They do not reserve money or logical units, acquire Shipment HOLD, verify source evidence, or prevent simultaneous requests. Production acceptance still requires atomic D/E persistence and a real E handover lock.
- Unit tests are database-free and do not prove concurrency, persistence, Shipment HOLD, or Member C/E evidence correctness.
- Unresolved: whether full physical Shipment cancellation refunds shipping; provider retry/query behavior; the authoritative start timestamp for the seven-day Course window; and whether refund amount can be partial within a selected Product unit.
