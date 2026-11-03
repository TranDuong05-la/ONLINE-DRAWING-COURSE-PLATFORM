# Payment Settlement Readiness

## Scope and source baseline

This review describes source behavior in the integrated repository as inspected on 2026-10-28. The requested baseline was HEAD `8baeced4f7aaa8dd8ed1d1d5c3ed0a236b3c91dc`; the inspected worktree is on `feature/member-d-commerce` at `bc4ef5a04e7f0af7ccf7d2717b2f9efe7b48125c` instead. The worktree was clean at inspection. No database or gateway was accessed.

VERIFIED below means present in source, not production-enabled or verified against MySQL.

## Approved settlement decision

One immutable successful settlement is recorded per Order, PaymentAttempt, and PaymentEvent, only in the same transaction that confirms the Order and finalizes local reservations. Duplicate success callbacks return the existing settlement without repeating effects. Settlement rows are never edited or deleted; refunds, chargebacks, and financial adjustments require separate records in a later phase. There is no PROCESSING settlement state.

## Verified existing components

| Component | Source | Existing behavior |
| --- | --- | --- |
| VNPay configuration | `backend/src/main/java/com/artacademy/payment/vnpay/VnPayProperties.java`, `OptionalSandboxPaymentConfiguration.java` | Configuration and beans are conditional on `app.payment.vnpay.enabled=true`; the default is disabled. No controller is registered. |
| Payment URL signing | `VnPayPaymentUrlBuilder.java`, `VnPaySignature.java` | Builds a deterministically sorted, encoded HMAC-SHA512 URL from persisted Order/PaymentAttempt snapshots; requires VND, positive amount within VNPay range, matching owner/order/amount/deadline, payable status, and an unexpired exclusive deadline. |
| Persisted session | `PaymentSessionApplicationService.createOrReuse(...)` | `@Transactional`; locks owner-scoped Order, then its attempts; reuses one active CREATED/PENDING attempt, refuses ambiguous or successful attempts, and retries only a clean FAILED attempt using a new merchant reference. It persists before signing within the same transaction, so signing failure rolls back the new attempt. There is no provider network call. The actual class is named `PaymentSessionApplicationService` (not `VnPayPaymentSessionService`). |
| Verified callback value | `VerifiedVnPayCallback.java`, `VnPayCallbackVerifier.verify(...)` | The value has package-private construction and immutable signed parameters. The verifier checks required fields, merchant code, amount/reference format, and HMAC-SHA512. The reconciliation service verifies the value again. No browser-return endpoint exists. |
| IPN classification/audit | `VnPayIpnReconciliationService.reconcile(...)`, `PaymentEventPolicy.java` | `@Transactional`; resolves by provider/merchant reference, locks Order then attempt, applies payment-attempt policy, writes one immutable `payment_events` row, or records conflicting facts in `payment_event_conflicts`. Exact event replay returns the existing audit. It can mark an attempt SUCCEEDED but deliberately does not change the Order or fulfill it. Unknown merchant references are classified but cannot be persisted with current foreign keys. |
| Payment persistence | `PaymentAttemptJpaEntity.java`, `PaymentEventJpaEntity.java`, `PaymentEventConflictJpaEntity.java` and their repositories; Flyway `V5__payment_audit.sql` | Unique provider/merchant reference, provider/transaction reference, provider/event reference, and conflict identity; immutable event/conflict snapshots; owner and Order foreign keys; optimistic versions on attempts. Events retain a SHA-256 payload digest, not the complete signed callback. |
| Order snapshots and transitions | `Order.java`, `OrderItem.java`, `OrderJpaEntity.java`, `OrderItemJpaEntity.java`, `OrderRepository.java`, `OrderPersistenceMapper.java` | Whole-VND totals and line/name/price/discount snapshots are immutable. Order creation sets PENDING_PAYMENT/CREATED and a 30-minute deadline. `Order.confirmPayment(...)` validates persisted success evidence, route, amount/currency, and both strict deadline checks. `OrderJpaEntity` exposes that guarded transition and `expireUnpaid(...)`; neither is exposed through a production workflow. |
| Checkout | `service/checkout/CheckoutCoordinator.java`, `CheckoutRequest.java`, `CheckoutIdempotencyService.java` | Coordinator is an unwired core; no production Checkout endpoint/bean is active. It checks completed replay before Cart revision, supports partial selections, snapshots trusted prices, and creates a pending Order. It leaves Cart contents intact. Its local writes and D-owned voucher reservation are intended to share one REQUIRED transaction. |
| Voucher lifecycle | `JpaVoucherReservationService.java`, `VoucherClaim.java`, `VoucherClaimJpaEntity.java`, `VoucherReservationPort.java` | The JPA adapter is a real D-owned service. Its operations require an existing caller transaction (MANDATORY). It can reserve, release after a terminal unpaid Order, and consume only after a confirmed/succeeded Order, with deadline checks and idempotent terminal handling. Usage counters and claims are locked and updated together. |
| Course/inventory boundary | `CoursePurchasePort.java`, `InventoryReservationPort.java` | Interfaces define claim/reserve, confirm, and release operations. Repository search found only test doubles; there are no production adapters in this worktree. |
| Catalog/shipping boundaries | `CourseCatalogPort.java`, `ProductCatalogPort.java`, `ShippingQuotePort.java` | Typed interfaces exist for Checkout, but no integrated production adapters were found. |

## Settlement state transition matrix

Settlement authority must be a cryptographically verified server-to-server IPN, validated against the persisted PaymentAttempt and Order. A browser return URL may display status but must never change payment, Order, voucher, ownership, or inventory state.

| Callback / persisted condition | PaymentAttempt | Order and reservations | Handling |
| --- | --- | --- | --- |
| Verified success, correct amount/currency, valid provider time, receipt and provider time both strictly before the 30-minute deadline, Order still payable | Mark SUCCEEDED once, retaining provider transaction and payment time | In one transaction set Order CONFIRMED/payment SUCCEEDED; consume voucher; confirm Course claims and Product reservations; append settlement evidence | Only automatic settlement candidate. Current IPN code does not perform these Order/claim transitions. |
| Verified failure with valid routing and amount | Record FAILED according to `PaymentEventPolicy` | Keep Order pending until its deadline; do not release claims just because an attempt failed | Another clean attempt may be started while Order remains payable. |
| Exact duplicate successful IPN | No second transition | No repeated consumption/confirmation | Return stored decision. If settlement and the original event are committed atomically, Order/settlement state makes this a no-op. Current reconciliation only deduplicates the audit event. |
| Same provider/event identity with different signed facts | Preserve a prior SUCCEEDED status; quarantine for review; retain conflict evidence | No Order or fulfillment changes | Manual reconciliation; never overwrite the first successful result. |
| Late provider success (receipt or trusted provider payment time at/after deadline) | Record success evidence with LATE_SUCCESS/review state; current policy can mark the attempt SUCCEEDED | Do not confirm Order, consume voucher, or confirm claims/reservations | Manual financial reconciliation/refund decision. Do not auto-fulfill. |
| Unknown merchant reference | No attempt exists to update | No Order/reservation changes | Current schema cannot persist this callback. Add a constrained unmatched-case record before operating an IPN endpoint. |
| Amount mismatch, fractional VND, or currency mismatch | Mark reconciliation required; preserve event evidence | No settlement or fulfillment | Manual review. VNPay callback contract is VND-only; callback has no independent currency field, so persisted Order/attempt currency must remain the authoritative VND check. |
| Order cancelled/expired before callback | Preserve callback evidence; mark success as late/not payable where applicable | Do not re-open or fulfill automatically | Manual reconciliation, including possible refund. |
| Missing/invalid/out-of-range provider payment timestamp | Reconciliation required; not an automatic success | No Order transition | Manual review. IPN receipt time is not a substitute for provider payment time. |
| Unknown/ambiguous status or response code | UNKNOWN/review | No Order transition | Manual review. |
| Concurrent repeat IPNs | Serialize through Order then attempt row locks; unique event key is the final guard | A successful settlement transaction must make subsequent requests no-ops | Current reconciliation locks rows; MySQL concurrency behavior still needs dedicated verification. |

The payment deadline is exclusive: equality with the deadline is expired. `PaymentEventPolicy` treats either receipt time or provider occurrence time at/after attempt expiry as late. Session URL expiry is based on the same Order deadline. `Order.isEligibleForExpiration(...)` excludes UNKNOWN and SUCCEEDED payment states. `OrderJpaEntity.expireUnpaid(...)` requires the deadline and a pending unpaid Order. Search found no production expiration worker invoking it or releasing Course/inventory claims; that lifecycle remains unimplemented.

## Transaction boundary and lock order

### Required settlement transaction

The proposed successful-settlement unit is one local database transaction:

1. Verify callback signature before trusting its fields; perform no network calls.
2. Resolve the attempt route, then lock the owner-scoped Order and PaymentAttempt. Preserve the existing order: Order first, PaymentAttempt second. Recheck route, owner, provider, amount, currency, Order state, and exclusive deadline under lock.
3. Find/insert the immutable payment event and enforce event uniqueness. Decide duplicate/conflict before side effects.
4. For eligible on-time success, transition PaymentAttempt and Order, then consume voucher and finalize local Course/Product claims, and persist settlement/outbox evidence. All must join the same REQUIRED transaction manager; any failure rolls everything back.
5. For failure, late, mismatch, ambiguous, expired, or conflict outcomes, persist evidence and review state only. Never perform fulfillment.

Do not call shipping, Course, Product, inventory, payment-provider, or other remote HTTP services inside this transaction. If fulfillment crosses process/service boundaries, write an outbox row in the transaction and let an idempotent worker retry external work. VNPay session URL creation is currently local signing only; it is not evidence VNPay accepted payment.

### Shared resource lock order to freeze before activation

Existing payment-session and IPN paths both lock **Order -> PaymentAttempt(s) by ID**. Voucher lifecycle locks **Order -> Voucher definition -> per-user usage -> VoucherClaim**. Checkout currently takes a voucher definition/usage lock during quote, then later reserves Product IDs in sorted order, Course IDs in sorted order, and persists the voucher claim. Define and document one shared-resource order before settlement is enabled. A workable target is:

**Order -> PaymentAttempt(s) by ID -> Voucher definition -> voucher usage -> VoucherClaim -> Product/inventory rows by ascending product ID -> Course purchase-claim rows by ascending course ID -> settlement/outbox row.**

Checkout has no pre-existing Order row to lock when creating a new Order, but its voucher/product/course lock sequence must be aligned with release, expiry, and settlement participants. All B/C/E adapters must use the same local MySQL datasource/transaction manager and implement atomic, idempotent database operations. Current B/C/E adapters do not exist, so this is a contract, not a verified guarantee. Do not claim external shipping or fulfillment is transactionally atomic.

## Persistence readiness and blockers

Flyway V1-V8 provides payment attempts/events/conflicts, voucher claims and counters, immutable Order/OrderItem and shipping-address snapshots, Checkout request idempotency, immutable settlement rows, and bounded reconciliation cases. There are still no refund, fulfillment-outbox, Course-claim, inventory-reservation, or shipment tables in these integrated migrations.

| Capability | Existing representation | Readiness |
| --- | --- | --- |
| Settlement idempotency | V8 `payment_settlements`, unique by Order, Attempt, and Event | Durable immutable ledger and replay reference exist. Coordinator tests cover duplicate replay; production coordinator remains disabled pending B/C adapters. |
| Late payment/manual review | Attempt `review_reason`, event disposition/reason, observed Order status, V8 reconciliation cases | Evidence can be represented, but there is no reviewer workflow/status/resolution UI. |
| Unmatched verified callbacks | V8 `payment_reconciliation_cases` supports nullable routes and bounded callback facts | Schema support exists. Production IPN persistence of unmatched callbacks and its endpoint remain unimplemented. Never store raw secrets or full callback query. |
| Conflicting callbacks | `payment_event_conflicts` with unique first-event/fingerprint and FKs | Representable for known attempts. No case resolution workflow. |
| Refund-required state | Review reasons can flag late/conflicting payment | No refund request/transaction lifecycle or refund-required state. Keep refunds a separate later phase; do not infer refund completion from a review flag. |
| Fulfillment pending/failed | No persistence | Blocker if any fulfillment side effect is external or retryable. Add a transactional outbox before enabling asynchronous fulfillment. |
| Course and inventory claims | Only Checkout port interfaces | Blocker. No production B/C/E entities, adapters, constraints, or same-transaction proof. |
| Order settlement transition | `Order.confirmPayment(...)` and `OrderJpaEntity.confirmPayment(...)` | Guarded transition exists and is covered by H2 tests. It is invoked only by the unregistered settlement coordinator. |

### Implemented persistence and remaining schema direction

V8 implements the immutable settlement ledger and bounded reconciliation-case tables described below. Do not edit V1-V8. Before production settlement, review any additional migration with teammates. Consider an outbox only if downstream fulfillment is asynchronous or external:

- A `fulfillment_outbox` only if downstream fulfillment is asynchronous/external. Key it uniquely by Order and action, track retry state/attempt timestamps, and have idempotent consumers. It is not a substitute for the local Course/inventory claim transaction.

Refund tables can wait for a dedicated refund phase unless business owners require an explicit refund-required queue now. These are schema proposals, not approved or implemented migrations.

## Member B/C/E integration contracts

| Member | Required production contract | Current finding |
| --- | --- | --- |
| B - Course | Trusted local Course lookup/status/price at Checkout; database-backed ownership/claim integration coordinated with C | `CourseCatalogPort` is present, but no production Course catalog adapter was found. No Course entities/table integration found. |
| C - Learning/ownership | Atomic `confirmForOrder(userId, courseId, orderId, confirmedAt)` and `releaseForOrder(...)`; claims unique by user/Course and Order, recoverable/idempotent; integrate already-owned and pending-purchase checks | `CoursePurchasePort` interface exists, but only test doubles implement it. No production ownership/pending claim adapter or claim schema found. |
| E - Product/inventory/shipping | Trusted Product lookup and purchasability; atomic stock reserve/confirm/release per Order; deterministic row locks and idempotency; trusted shipping quote/snapshot | `ProductCatalogPort`, `InventoryReservationPort`, and `ShippingQuotePort` interfaces exist. No production Product, inventory, reservation, or shipping adapters/tables found. |

Before implementation, B/C/E must confirm identifier types, transaction manager/data source, table ownership, claim uniqueness and lock semantics, reservation expiry, terminal-state rules, and whether each operation is local database work or external. Checkout cannot be activated with test doubles or no-op providers.

## Checkout and payment lifecycle observations

- Checkout supports partial selection and checks Cart revision under its Cart lock. It snapshots trusted catalog names/prices and never accepts client totals. Cart is not cleared after creating a pending Order, so the selected items remain in Cart; the UI/API must explain this and avoid implying the cart was consumed.
- The Order and PaymentAttempt share the exclusive 30-minute deadline. Failed payment attempts do not cancel an unexpired Order; a later session may create a distinct attempt only after a clean FAILED state.
- Active pending Course purchase checks use existing pending Orders; current concurrent claim protection still depends on missing C implementation.
- Voucher claims are reserved during Checkout and remain tied to Order terms; current JPA release requires terminal unpaid state. Successful consume requires Order already CONFIRMED/COMPLETED and payment SUCCEEDED. Those transitions are not implemented in the settlement flow.
- The unregistered `OrderExpirationCoordinator` locks the Order, attempts by ID, settlement, and payment evidence; it applies the exclusive deadline and releases Voucher, Product, and Course reservations in one REQUIRED transaction. Unknown, successful, conflicting, ambiguous, or unresolved payment evidence requires review. It has no production scheduler or B/C release adapters.
- No VNPay HTTP call is made when creating a payment URL. Session expiry is local and cannot guarantee provider-side session revocation.

## Recommended next phases

1. **B/C integration:** implement real Course ownership confirmation/release and Product inventory confirmation/release against their owning persistence models, sharing the Order transaction manager and specified lock order.
2. **Production workflow wiring:** add a bounded expiration worker only after release adapters exist; keep settlement disabled until confirmation adapters are integrated and same-transaction behavior is demonstrated.
3. **Integration verification:** repeat guarded MySQL concurrency and rollback tests with the real B/C adapters. The reported 10/10 settlement/expiration IT result uses test-only Course/Inventory adapters and does not establish their production behavior.
4. **Operational ingress:** only after reconciliation and adapter readiness, review a server-to-server IPN endpoint with strict size/rate limits, no sensitive callback logging, and durable retry handling. Keep browser return read-only.
5. **Later refund phase:** define refund eligibility, partial refund allocation, provider refund retries, and auditable refund states separately; never auto-fulfill or auto-refund late success without an approved policy.

## Decisions required before enabling payment

1. Is an on-time provider payment timestamp sufficient for settlement when the IPN is received before the deadline, or must both provider payment time and receipt be strictly before the deadline? Current `PaymentEventPolicy` requires both to be before.
2. How long may a verified IPN arrive after the Order deadline before it becomes a manual-review case, and who resolves late success/refund decisions?
3. Should every unmatched signed callback be retained, and what privacy/retention limits apply to its minimal evidence?
4. Immutable settlement is approved: one settlement per Order/Attempt/Event, inserted atomically with confirmation and never edited; financial adjustments use separate records.
5. Which local Course/inventory reservation transitions are part of the settlement transaction, and which downstream effects require an outbox?
6. How should Orders with UNKNOWN attempts be handled by the expiry worker?
7. What is the policy when cancellation/expiration wins the Order lock immediately before an otherwise on-time provider success is processed?
8. Does keeping selected items in Cart after Checkout match the intended user experience, or should a later phase remove only the purchased quantities after Order commit?
