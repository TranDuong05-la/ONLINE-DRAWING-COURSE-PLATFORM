# Checkout Core Preparation

Status: preparation only. There is no Checkout controller or production coordinator bean, and no payment-session call. The D-owned voucher reservation service is registered; production B/C/E adapters are not.

## Existing components reused

- `service/checkout/CheckoutRequest`: validates selected Cart item IDs and quantities, normalizes text, sorts selections, and computes a versioned SHA-256 fingerprint from Cart revision, selection IDs/quantities, normalized voucher code, and the optional shipping address. It contains no owner ID or client price.
- `service/checkout/CheckoutIdempotencyService`: owner-scoped durable claim/replay implementation over `checkout_requests`. It requires an enclosing transaction; `PROCESSING` is not committed separately. The unique `(user_id, idempotency_key)` race requires rollback and retry.
- `entity/CartJpaEntity`, `repository/CartRepository.findByUserIdForUpdate`, `service/CartPersistenceMapper`, and `service/CartState`: existing Cart row lock, server revision, and persisted item identity/quantity.
- `entity/Order`, `OrderLineSnapshot`, `OrderVoucher`, `OrderDiscountAllocator`, `OrderPersistenceService`, and `StoredOrder`: whole-VND immutable snapshots, deterministic discount allocation, fixed exclusive 30-minute deadline, and REQUIRED persistence.
- Existing active pending Course purchase query: reused through `ActivePendingCoursePurchaseProvider` as the read-side contract.

## Typed integration ports

All ports are in `service/checkout`. B/C/E ports have no production provider registration; the D voucher participant is registered:

- `CourseCatalogPort.findForCheckout(Integer courseId)` returns an optional trusted name, whole-VND unit price, and purchasable state (B).
- `ProductCatalogPort.findForCheckout(Integer productId)` returns the equivalent trusted Product offer (E).
- `CoursePurchasePort` extends `ActivePendingCoursePurchaseProvider`, adds owner-scoped ownership lookup, and atomic `claimForOrder`; `confirmForOrder` and `releaseForOrder` define future lifecycle operations (C).
- `InventoryReservationPort.reserveForOrder` atomically reserves requested quantity for an Order; confirm/release are future lifecycle operations (E).
- `ShippingQuotePort.quoteFeeVnd` returns a trusted whole-VND fee for Product quantities and optional address (E).
- `JpaVoucherReservationService` implements `VoucherReservationPort`: `quoteAndLock` validates and locks a voucher definition/capacity; `reserveForOrder` writes its Order-linked claim; `releaseForOrder` and `consumeForOrder` transition reserved claims and counters inside the caller's transaction (D).
- `CheckoutCartPort`, `CheckoutOrderPort`, and `CheckoutIdempotencyPort` isolate the existing D persistence primitives for orchestration and tests.
- `CheckoutRejectedException` provides typed future application-boundary outcomes without activating HTTP mappings.

## Coordinator and transaction contract

`CheckoutCoordinator` is a plain, explicitly constructed class, not a Spring component. Its `TransactionTemplate` uses REQUIRED propagation. The composition must use the same local transaction manager and database for Cart, Order, idempotency, voucher claims, Course claims, and inventory reservations. Providers must perform concurrency-safe writes; a prior availability/ownership read is not a reservation. No provider may make remote HTTP calls while the transaction is open.

The implemented flow checks the durable idempotency key/fingerprint first, returns a completed owner-scoped Order replay immediately, and only then locks the Cart and checks its revision and selected item quantities. It reads trusted catalog offers, checks Course ownership and active pending purchases, calculates the immutable Order snapshot, obtains a shipping quote, then persists the Order and applies inventory, Course, voucher, and idempotency completion writes in the same transaction. Voucher locking is ordered as definition row then per-user usage row; the definition lock serializes capacity checks even when the per-user row does not yet exist. Product IDs and Course IDs are each processed in ascending order; integrations must keep a documented global resource-lock order and avoid acquiring these resources in an inconsistent order elsewhere.

The Order is inserted before reservation writes because the existing `voucher_claims.order_id` relation and `VoucherClaim.reserved(...)` require an Order identity and snapshot. This remains atomic only while every participant joins the same transaction. Any exception rolls all database writes back. The H2 transaction test exercises this using test-only participant rows; it does not prove that future member adapters join that transaction manager.

The application boundary still needs to obtain the Integer user ID from A's verified principal and ensure the `OrderCurrentUser` used by `OrderPersistenceService` resolves the same identity. Cart `REQUIRES_NEW` services must not be called from this flow. Payment session creation, if later added, must happen after commit and is not atomic with this database transaction.

## Boundaries and unresolved integration decisions

- B/C/E production entities, repositories, adapters, tables, and ownership/stock ledgers are absent. Do not activate this coordinator until real local implementations are available.
- `CoursePurchasePort` intentionally requires an atomic owner-scoped claim. C must identify the authoritative ownership record, the pending claim record/constraint, behavior for duplicate claims, and when settlement/cancellation may confirm or release it. Retryable payment failure must not release a claim.
- E must provide atomic stock reservation and release semantics, including insufficient stock and idempotency by Order; Cart reads never reserve stock.
- B/E must provide trusted local catalog projections. They must not call remote services in the Checkout transaction.
- Shipping address persistence is a member-owned integration decision; current Order snapshots accept the normalized request address, while shipping fee is provided by the typed port.
- The exact Cart policy after successful partial Checkout (remove selected lines, decrement quantities, or leave Cart unchanged) is not settled by the existing Cart contract and must be decided before exposing an endpoint. The coordinator currently leaves Cart contents/revision unchanged.
- The D voucher adapter now joins the caller transaction and persists immutable claims/counters. Payment settlement and Order cancellation/expiration orchestration are still absent; callers must first persist `CONFIRMED`/`SUCCEEDED` for consumption or a terminal unpaid Order state for release. Late payment evidence remains a separate reconciliation concern.
- Same-key concurrent requests that lose the unique insert race fail the current transaction with a typed retryable conflict; the client must retry after the winning transaction completes to receive replay. No separate durable PROCESSING transaction is used.
- Product/Course and Cart locks need a team-wide ordering decision if other workflows lock the same rows in reverse order. Deadlock retries, if desired, belong at the future application boundary and must preserve the same idempotency key.

## Verification added

- `CheckoutCoordinatorTest` contains pure unit tests with explicit fakes. It covers mixed trusted snapshots/totals, replay before Cart access, fingerprint mismatch, revision and selection rejection, unavailable/owned/pending items, stock and voucher failures, and retry without a duplicate Order.
- `CheckoutCoordinatorJpaH2IntegrationTest` uses the isolated test-profile H2 database, real Cart/Order/idempotency JPA, and an explicitly test-only SQL effects table. It verifies commit and rollback across Order, idempotency, and test participant writes, and replay after Cart revision changes. These tests establish local H2 transaction behavior, not MySQL behavior or production adapter enlistment.
- `VoucherReservationH2IntegrationTest` uses real voucher, usage, claim, and Order repositories to verify quota locking, duplicate handling, lifecycle counter transitions, campaign deactivation/expiry behavior, and rollback through Checkout. Its simulated settlement/cancellation state is test setup only; it does not implement payment or Order lifecycle workflows.

No MySQL database was accessed and no migration was added or run in this phase.
