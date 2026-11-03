# D Integration 2: Checkout and Payment Readiness

**Assessment:** source-only review. No database connection, migration, or runtime profile was used.

## Repository state

- Branch: `feature/member-d-commerce`
- HEAD: `447a3c0c3723ca67d36a98452c5fd449679b850c`
- Worktree was already two commits ahead of its remote. Existing untracked Refund documents, pure Java policies, state machine, and tests were preserved.

## Dependency matrix

| Responsibility / owner | Existing contract and implementation | Schema / transaction facts | Readiness |
|---|---|---|---|
| Course catalog and trusted price — B | `checkout/CourseCatalogPort` and `CourseCartCatalogProvider` define trusted read contracts. No production implementation or Course entity/repository was found. | No Course catalog table exists in V1–V8. Any adapter must read B's authoritative datasource locally and must not make a remote call under Checkout's transaction. | **BLOCKED** |
| Course ownership — C | `CourseOwnershipProvider` is a read contract only. `CoursePurchasePort` defines ownership checks, pending check, claim, confirm, and release, but has no implementation. `JpaActivePendingCoursePurchaseProvider` is implemented over D's own Order/OrderItem tables and only detects active pending D Orders. | No Enrollment, Course ownership, or pending-claim ledger exists in V1–V8. The pending-order query is a preflight, not an atomic C claim and cannot prevent simultaneous claims by itself. | **BLOCKED** |
| Product catalog and trusted price — E | `checkout/ProductCatalogPort` and `ProductCartCatalogProvider` are read contracts only. No production Product entity, repository, or adapter was found. | No Product table exists in V1–V8. | **BLOCKED** |
| Product stock reservation and release — E | `checkout/InventoryReservationPort`, `settlement/InventoryReservationConfirmationPort`, and `expiration/InventoryReservationReleasePort` define reserve/confirm/release boundaries. No production implementation exists. `ProductStockAvailabilityProvider` is only a Cart read contract. | No Inventory, stock, or reservation table exists in V1–V8. No same-datasource/transaction-manager participation or lock order can be verified. | **BLOCKED** |
| Shipping quote and address — E / Auth | `checkout/ShippingQuotePort` accepts authenticated user, product quantities, and an optional request address. No quote implementation exists. Auth does have `user_addresses` in V1, but Checkout does not resolve or validate a submitted address against that owner-scoped table. | `orders.shipping_fee_vnd` and `order_shipping_addresses` snapshot a fee/address. No Shipment or shipping-rate table exists in V1–V8. The address and quote must be authoritative before snapshotting. | **BLOCKED** |
| Voucher reserve, consume, release — D | `JpaVoucherReservationService` is a production `@Service` implementing reservation, settlement consumption, and expiration release ports. It uses the shared `PlatformTransactionManager`, locking Voucher/usage/claim rows and preserving per-Order snapshots. | `vouchers`, `voucher_user_usage`, and `voucher_claims` are in V4. Service operations require the enclosing local transaction for Checkout/settlement/expiration atomicity. | **READY as a D participant; end-to-end use awaits coordinator wiring** |
| Cart persistence and revision — D | `CartJpaEntity`, repositories, `CartApplicationService`, and `JpaCheckoutCartPort` support owner-scoped locked Cart reads and revision. `CartApplicationService` and `JpaCheckoutCartPort` are explicitly not scanned beans. Availability projection has no production catalog/ownership/stock providers. | `carts`/`cart_items` are V2; server-managed revision is V6. The configured primary JPA datasource/transaction manager is the local application datasource. | **PARTIAL** |
| Order snapshot and idempotency — D | `Order`, `OrderItem`, `OrderPersistenceService`, `OrderPersistenceCheckoutPort`, `CheckoutIdempotencyService`, and `CheckoutCoordinator` exist. Coordinator enforces replay before Cart revision check, selected-item validation, trusted offer reads, deterministic resource ordering, and one REQUIRED transaction. Several adapters/services are plain classes, and there is no Checkout bean configuration or endpoint. | `orders`/`order_items`/shipping snapshot are V3; `checkout_requests` is V7. Successful Order insert and idempotency completion are designed to commit together. V7 PROCESSING claim is not committed separately. | **PARTIAL: D core exists; no production assembly or endpoint** |
| VNPay session and callback audit — D | `VnPayPaymentUrlBuilder`, `PaymentSessionApplicationService`, `VnPayCallbackVerifier`, and `VnPayIpnReconciliationService` exist. `OptionalSandboxPaymentConfiguration` registers session and IPN reconciliation services only when `app.payment.vnpay.enabled=true`. No Payment/return/IPN controller was found. Reconciliation classifies and records verified events; it does not settle an Order. | `payment_attempts`, `payment_events`, and `payment_event_conflicts` are V5. The V8 `payment_reconciliation_cases` table exists, but the current reconciliation service is not wired to its repository. Unknown merchant references return without persistence because the V5 event route requires known Attempt/Order/User FKs. | **PARTIAL: service foundations exist; no route or unmatched-callback persistence** |
| Settlement — D with C/E participants | `SettlementCoordinator` and `JpaSettlementLedgerAdapter` exist. Optional config requires enabled VNPay/settlement flags plus Course confirmation, Inventory confirmation, Voucher consumption, and ledger beans. Voucher service exists; Course/Inventory production adapters do not. `JpaSettlementLedgerAdapter` is not itself registered as a bean by the shown configuration. | V8 adds immutable `payment_settlements` and reconciliation cases. Coordinator wraps Order → Attempt → Voucher → Product IDs → Course IDs → ledger work in REQUIRED transaction; B/C/E must share its transaction manager for atomic confirmation. | **BLOCKED** |
| Expiration and reservation release — D/C/E | `OrderExpirationCoordinator` is a pure/unregistered coordinator requiring Voucher, Inventory, and Course release ports. Voucher release is supplied by D's `JpaVoucherReservationService`; Course and Inventory release implementations are absent. No expiration configuration or scheduler was found. | V3 Order deadline, V5 payment audit, V8 settlement evidence, and V4 voucher claims are available. No C/E claim/reservation tables exist to release. | **BLOCKED** |
| Order History — D | `OrderHistoryController`, `OrderHistoryQueryService`, `AuthenticatedOrderCurrentUser`, and owner-scoped repositories are registered. Existing GET `/api/orders` and `/api/orders/{orderId}` read persisted snapshots. | Uses V3 Order/OrderItem/address tables and Auth identity. Read-only REQUIRED transactions. | **READY for existing snapshot history** |

### Shared transaction and datasource assumptions

The application config uses one Spring-managed JPA datasource (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) and auto-configured `PlatformTransactionManager`; `application-mysql-local.yml` enables Flyway with Hibernate validation. Checkout, settlement, expiration, and voucher code use `TransactionTemplate`/`@Transactional` with REQUIRED behavior where their documentation says they join the caller. This is a viable local atomic boundary only if B/C/E adapters use that same datasource and transaction manager. The repository contains no evidence that their production stores do so. Do not substitute HTTP/remote operations inside these transactions.

## Production activation audit

| Flow | Current gate/registration | Why it is not production-ready |
|---|---|---|
| Cart REST | `CartController` is conditional on `app.cart.api-enabled=true`; no default property enables it. | No trusted production Course/Product/ownership/stock provider exists; `CartApplicationService` and projector are not assembled as beans. Enabling the flag alone is insufficient and startup may fail due to missing dependencies. |
| Checkout | `CheckoutCoordinator` is an unwired core; no Checkout controller, Spring bean configuration, or Checkout feature flag was found. `JpaCheckoutCartPort`, `OrderPersistenceCheckoutPort`, and `CheckoutIdempotencyService` are not beans. | All B/C/E catalog, ownership claim, stock reservation, and shipping ports lack production implementations. Coordinator cannot be safely assembled. |
| Payment session | VNPay configuration defaults to disabled (`VNPAY_ENABLED:false`). If enabled, optional config binds settings and registers local session/reconciliation services. | No endpoint invokes the session service. Required TMN code, secret, HTTPS return URL, and HTTPS IPN URL must validate. URL generation is not provider acceptance. |
| IPN audit | No production IPN controller or route exists. | A trusted callback route must preserve repeated query parameters, verify signatures, and return provider protocol responses. Reconciliation currently does not persist unmatched merchant references, nor use V8 reconciliation cases. |
| Settlement | `app.payment.settlement.enabled` has no enabled default. Optional bean also requires VNPay enabled and all settlement participant beans. | Course and Inventory confirmation adapters are absent; the D ledger adapter is not registered by the provided config. No controller calls reconcile then settle. |
| Expiration | Coordinator exists as a plain class only; no bean configuration, scheduled task, or endpoint exists. | C/E release adapters are absent. A scheduler must only process due pending Orders and preserve the current REQUIRES_REVIEW behavior for any unresolved payment evidence. |
| Order History | Controller/service are active under authenticated routes. | No checkout-order creation is exposed, so history can display only Orders created by another integrated path. |

There are no current Checkout or Payment API routes to conflict with `/api/orders`. The existing Cart route is separate. Any future endpoint should use the existing JWT principal resolver and must not accept a client user ID, price, total, or payment outcome as authoritative.

## Cross-module schema audit

V1–V8 create Auth/profile/address, Cart, Order/order item/shipping-address snapshot, Voucher/claims, payment attempts/events/conflicts, Checkout idempotency, settlement, and payment reconciliation-case tables. They do **not** create Course catalog, Course ownership/enrollment/purchase-claim, Product catalog, inventory/stock/reservation, Shipment, Shipment item, carrier handover, shipping quote/rate, or fulfillment tables. Existing entities and ports in Member D do not imply those schemas exist. No migration should duplicate B/C/E ownership; each owner must identify the authoritative table and transaction contract.

## Minimal end-to-end implementation order

| Step | Intended outcome | Status / prerequisite |
|---|---|---|
| 1. Authenticated customer edits Cart | JWT-owned Cart item CRUD and authoritative availability display | **Needs B/E contracts and D bean assembly.** Current API is disabled, and providers are absent. |
| 2. Customer selects items and submits Checkout | Owner-scoped Cart revision and partial selection validated | **D core already implemented; needs wiring and endpoint.** Completed idempotency replay is checked before revision. |
| 3. Trusted quote and immutable Order | Catalog prices, eligibility, shipping, voucher, address snapshots | **D domain/persistence exists; blocked** on B Course offer, C ownership/pending claims, E Product/stock/shipping, and owner-scoped address validation. |
| 4. Atomic reservations | Stock and Course claims created with Order, voucher, and idempotency completion | **Unsafe until C/E supply same-transaction atomic claim/reserve ports** with uniqueness and lock ordering. Read-before-write checks alone are insufficient. |
| 5. Initiate VNPay | Persist/reuse payment attempt and return signed URL from persisted Order | **Core exists; needs authenticated payment-session route, HTTPS callback routes, credentials/secrets, and abuse/rate controls.** No external gateway is called by current builder. |
| 6. Verified IPN to settlement | Verify callback; persist event; settle exactly once; confirm Order/claims/voucher and immutable ledger | **Settlement core exists; blocked** on IPN route, C/E confirmation participants, explicit D ledger bean/config, and a documented reconciliation-to-settlement call boundary. Settlement is not invoked automatically today. |
| 7. Expire unpaid Orders | At/after exclusive deadline release voucher, stock, and Course claims | **Coordinator exists; blocked** on C/E release adapters, bean/config, and monitored scheduler. Payment audit evidence must continue to block unsafe automatic release. |
| 8. Order History | Customer reads own Order snapshots | **Already implemented** for persisted Orders via authenticated GET routes. |

The shortest credible demo is not a D-only code path: first integrate real B Course offers, C ownership/pending claims, and E Product/stock/shipping adapters on the same local transaction manager; then assemble Cart/Checkout behind explicit flags, add authenticated Checkout/payment-session and IPN controllers, invoke settlement only for eligible verified evidence, then add guarded expiration scheduling. Keep each flow disabled until its own required adapters and integration tests are present.

## Responsibility split and next actions

- **Member B:** authoritative local Course lookup, display name, trusted VND price, active/purchasable semantics, freshness and transaction behavior.
- **Member C:** authoritative ownership lookup plus concurrency-safe per-user/Course pending claim, unique/locking guarantee, settlement confirmation and final-unpaid release operations.
- **Member E:** authoritative Product details/price/status, stock availability and atomic per-Order reservation/confirmation/release, shipping quote, and Shipment/fulfillment integration if required by the demo. Confirm tables, same datasource/transaction manager, and lock order.
- **Member A/Auth:** provide or confirm a trusted owner-scoped saved-address selection contract; current V1 address tables exist but Checkout does not validate address ownership.
- **Member D:** assemble the existing D Cart/Checkout/idempotency/voucher/order/payment/settlement/expiration cores, add controllers only after providers are real, wire explicit feature gates, implement V8 unmatched/conflicting reconciliation persistence where required, and maintain Order History.

### Recommended next phase

Request B/C/E's actual entity/table names, repositories/services, transaction manager, uniqueness constraints, locking order, and exact method contracts. Then implement **D-Integration-3: production adapter integration for the first available real provider**, beginning with read-only Course/Product lookup and H2 adapter tests; do not register Checkout or enable Cart until every required provider for the selected path is real. For full Checkout, close C/E atomic reservation and release contracts first. For payment, implement IPN routing and unmatched-callback reconciliation persistence before wiring the settlement coordinator.

## Test strategy

1. Adapter contract tests against the actual B/C/E repositories, not fake production catalog values.
2. D isolated H2 persistence tests for owner scoping, revision/replay, Order snapshot, voucher transaction rollback, and payment evidence routing.
3. Real multi-transaction H2 tests for coordinator rollback and application wiring; mocks alone do not prove atomic database participation.
4. Guarded MySQL tests only on `drawing_course_it` for V8 schema validation and lock/uniqueness behavior after the relevant migrations are approved. Never infer MySQL concurrency guarantees from H2.
5. End-to-end authenticated API tests for Cart, Checkout, session creation, IPN response behavior, Order History, and expiration scheduling. No production feature flag should be enabled before its complete dependency-specific tests pass.

No tests were run for this read-only assessment. No source, tests, migrations, configuration, or database state were changed.
