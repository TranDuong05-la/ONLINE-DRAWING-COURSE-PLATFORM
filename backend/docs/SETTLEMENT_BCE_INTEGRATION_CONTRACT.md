# Settlement B/C/E Integration Contract

## Status and scope

This contract describes the integrated source at the time of the D-Payment-2D3 audit. It is a prerequisite for wiring the settlement coordinator, not an activation approval. The coordinator remains disabled unless settlement and VNPay are explicitly enabled and all required adapters are present. There is no production settlement/IPN REST endpoint. No production Course ownership confirmation or Inventory confirmation adapter is currently registered.

The contract covers only local database state transitions. No Course, Inventory, Shipping, or payment-provider HTTP call may run inside the settlement transaction.

## Existing and required ports

| Responsibility | Existing contract | Settlement contract | Status |
| --- | --- | --- | --- |
| Course ownership claim | `CoursePurchasePort.claimForOrder(userId, courseId, orderId, observedAt, expiresAt)` | `CourseOwnershipConfirmationPort.confirmCourseForOrder(userId, courseId, orderId, confirmedAt)` | Claim contract exists; settlement adapter is missing. Confirmation must finalize the existing Order claim and grant ownership idempotently. |
| Product reservation | `InventoryReservationPort.reserveForOrder(userId, productId, quantity, orderId, expiresAt)` | `InventoryReservationConfirmationPort.confirmProductForOrder(userId, productId, orderId, confirmedAt)` | Reservation contract exists; production reservation and settlement adapter are missing. Confirmation must consume the existing reservation, not decrement stock a second time. |
| Voucher claim | `VoucherReservationPort.consumeForOrder(userId, orderId, paymentReference, providerPaidAt, observedAt)` | `VoucherConsumptionPort.consumeForOrder(...)` | D-owned `JpaVoucherReservationService` implements both contracts. It requires an active transaction and checks the persisted Order is confirmed/succeeded before consuming. |
| Settlement ledger | `SettlementLedgerPort.findForUpdateByOrderId(orderId)` and `insert(orderId, attemptId, eventId, providerPaidAt, settledAt)` | Same | JPA adapter persists immutable V8 settlement evidence. Unique Order, Attempt, and Event references are the final duplicate guard. |

Course and Inventory adapters must reuse the owning members' existing claim/reservation tables and rules. Do not introduce duplicate Enrollment, Course ownership, Product, or stock tables in Member D.

## Transaction and datasource contract

All local writes must use the same Spring `PlatformTransactionManager`, datasource, and physical MySQL database as Orders, PaymentAttempts, payment events, voucher claims, Course claims, and Inventory reservations. Adapter calls must join the coordinator's `PROPAGATION_REQUIRED` transaction. They must not use `REQUIRES_NEW`, manually commit, open a second datasource, or defer database writes to an after-commit callback.

The coordinator owns a REQUIRED transaction when invoked at a transaction boundary. A transient persistence exception is reported as `RETRYABLE_FAILURE` only after that transaction template has completed rollback. When invoked inside an existing transaction, transient persistence failures propagate to the outer transaction owner; the coordinator does not claim rollback is complete. The caller must not catch such an exception and commit the outer transaction.

When the coordinator joins an existing transaction successfully, its `SETTLED` result is provisional until the outer transaction commits. The application boundary must not report durable settlement or perform any external action before that commit.

Adapter failures must be thrown through the transaction boundary. Do not catch a failed Course/Inventory/Voucher/ledger write and return a successful result. Any failure before commit must roll back Order confirmation, voucher consumption, Course ownership changes, Inventory reservation confirmation, and settlement insertion together.

H2 integration tests verify rollback using test adapters that insert marker rows into the same H2 transaction. This proves coordinator transaction composition with that test datasource; it does not prove future B/C production adapters join the MySQL transaction.

## Lock order and uniqueness

Use this shared acquisition order for the settlement lifecycle:

1. Order row (`orders.id`, pessimistic write lock).
2. PaymentAttempt row (`payment_attempts.id`, pessimistic write lock).
3. Settlement row by Order, using a locking/current read; settlement rows are immutable.
4. Voucher definition, per-user usage, then voucher claim (the existing voucher service order).
5. Product/Inventory reservation rows in ascending `product_id` order.
6. Course purchase-claim rows in ascending `course_id` order.
7. Insert the settlement ledger row after all required business operations succeed.

The current Checkout path takes its idempotency claim and Cart lock before shared resources. Voucher quote locks the Voucher definition and per-user usage before Checkout reserves Inventory products in ascending order and Course claims in ascending order. Voucher reservation then reuses those locks and inserts the claim. Settlement uses Voucher -> Inventory -> Course in the same shared-resource order. Payment session, IPN reconciliation, and settlement all lock Order before PaymentAttempt. The existing Order expiration method has no production coordinator; a future expirer must lock Order first, then PaymentAttempts by ID, and release Voucher -> Inventory -> Course in the same order.

Checkout currently invokes `ShippingQuotePort.quoteFeeVnd(...)` while its database transaction and voucher locks are held. The production adapter must be a local deterministic calculation; if it calls a remote shipping service, that call must move outside the transaction and its quote must be revalidated before creating the Order. This does not alter settlement behavior, but it is a checkout integration risk because it can extend lock duration or block while holding shared database locks.

Required idempotency keys/constraints:

- One Course claim per `(user_id, course_id)` for the active/owned lifecycle, with claim state and Order association transitions protected atomically.
- One Inventory reservation per `(order_id, product_id)`; repeated reserve/confirm/release for the same Order is idempotent and must not change stock or reservation counters twice.
- Existing Voucher claim uniqueness by Order and voucher/user usage limits; consume/release must update both total and per-user counters exactly once.
- Existing settlement uniqueness on `order_id`, `payment_attempt_id`, and `payment_event_id`.
- Existing payment event uniqueness on `(provider, event_reference)` and conflict identity uniqueness.

The B/C/E owners must confirm exact table and constraint names before adapters are implemented; this document does not assert those unintegrated schemas exist.

## Failure and replay semantics

- Same valid event and matching settlement row: return `ALREADY_SETTLED`, with no business effect repeated.
- Same Order but a different Attempt/Event after settlement: return `REQUIRES_REVIEW`; never overwrite the settlement or repeat fulfillment-related local operations.
- Valid failure: `NOT_ELIGIBLE` for settlement. Keep the Order pending until its existing exclusive deadline; a later clean payment attempt may succeed.
- Late, conflicting, amount/currency-mismatched, unknown, or ambiguous evidence: `REQUIRES_REVIEW`; no Order, Voucher, Course, or Inventory transition.
- Attempt status `SUCCEEDED` alone is insufficient. The coordinator also reloads the immutable event and requires an applied success audit, verified valid provider timestamp marker, matching route/amount/currency, and both provider payment time and IPN receipt time strictly before the persisted deadline.
- A transient database failure after coordinator-owned rollback may return `RETRYABLE_FAILURE`; retry uses the same persisted Event and is safe because Order locking, duplicate lookup, and unique constraints prevent repeated effects.
- Non-transient persistence or adapter failures propagate and roll back. Do not convert validation/ownership/claim errors to retryable infrastructure results.

## Integration implementation requirements

### Member C — Course ownership

- Provide a production bean implementing `CourseOwnershipConfirmationPort` by adapting the existing Course purchase-claim/ownership model.
- Confirm only the claim belonging to the specified user, Course, and Order.
- Make repeated confirmation idempotent and serialize concurrent confirmation against the same claim.
- Participate in the same datasource and REQUIRED transaction. A failed confirmation must throw so the entire settlement rolls back.
- Do not grant ownership for late or review-required callbacks.

### Member E — Inventory

- Provide a production bean implementing `InventoryReservationConfirmationPort` using the existing reservation model.
- Confirm only the reservation belonging to the specified user, Product, and Order; do not reserve or decrement physical stock again at settlement.
- Make repeated confirmation idempotent and protect against concurrent release/confirmation.
- Participate in the same datasource and REQUIRED transaction. Do not call shipping or fulfillment services here.

### Member B — Catalog boundary

- Order settlement uses the already persisted OrderItem name, price, quantity, and amount snapshots; it must not reprice an existing Order from the current catalog.
- Confirm the canonical Course identity and whether any post-payment Course state transition belongs in the ownership adapter. Do not make the settlement adapter depend on mutable catalog price/name data.

### Member D — Voucher and ledger

- Keep `JpaVoucherReservationService.consumeForOrder` in the same REQUIRED transaction after the Order has been changed to CONFIRMED/SUCCEEDED in the persistence context.
- Insert exactly one immutable settlement row only after Voucher, Product, and Course operations return successfully.
- Keep refunds, chargebacks, and financial adjustments outside this settlement row.

## Activation blockers

1. Production Course ownership confirmation adapter and its actual uniqueness/locking contract.
2. Production Inventory reservation confirmation adapter and its actual uniqueness/locking contract.
3. Confirmation that B/C/E persistence uses the same local MySQL datasource and transaction manager.
4. Repeat the guarded MySQL concurrency and rollback tests with real B/C adapters; the reported 10/10 settlement/expiration tests use transaction-bound test adapters.
5. A production expiration/release workflow using the shared lock order; the current coordinator remains unregistered and has no scheduler.
6. A decision on external fulfillment after settlement. If an external call is required, add a separately reviewed transactional outbox; do not make the network call inside settlement.
7. Keep `app.payment.settlement.enabled` false until the blockers above are resolved and separately approved. Keep the IPN endpoint disabled until unknown callbacks can be durably audited and the endpoint security/rate-limit/observability design is reviewed.

## Source-grounded findings

- Verified: `VnPayIpnReconciliationService` verifies the callback again, locks Order then Attempt, writes immutable event/conflict evidence, and does not confirm the Order.
- Verified: `Order.confirmPayment` requires persisted APPLIED success evidence, matching snapshots, the valid timestamp marker, and strict exclusive-deadline checks. It changes only Order/payment statuses.
- Verified: the settlement coordinator reloads the event, locks Order then Attempt, takes a locking read for an existing settlement, applies required ports in shared-resource order, and inserts the ledger last.
- Verified: the coordinator bean is conditional on explicit settlement and VNPay enablement and required ports. Course and Inventory production adapter beans are absent, so it is not registered in the current application context.
- Verified: Voucher consumption is D-owned JPA persistence and requires a caller transaction.
- Verified per the reported results: guarded V1-V8 MySQL compatibility suite passed 23/23 and settlement/expiration concurrency passed 10/10 against drawing_course_it. The concurrency suite uses test-only Course/Inventory adapters; it does not verify future B/C/E production adapters.

## Audit findings by severity

### D-Payment-2F checkpoint update

The user reports 278/278 isolated H2 tests, 23/23 guarded MySQL tests, and 10/10 settlement/expiration concurrency tests passing. The MySQL concurrency tests use transaction-bound test-only Course/Inventory adapters. Since the D-Payment-2D3 audit, `OrderExpirationCoordinator` has been prepared as an unregistered core; no production scheduler or Course/Inventory release adapters exist. The ranked findings below record the earlier audit and should be read with this update.

1. **Blocker — production adapters absent:** no production Course ownership or Inventory confirmation bean exists. Conditional wiring correctly leaves the coordinator unavailable until both are supplied.
2. **High — transaction contract must be demonstrated:** source interfaces cannot force a future adapter to use this same transaction manager. B/C/E must provide real persistence integration tests proving rollback and idempotency with the MySQL datasource before activation.
3. **Medium — expiration lifecycle absent:** `OrderJpaEntity.expireUnpaid(...)` is a domain/persistence transition only; no production worker orchestrates attempt locking and Voucher/Course/Inventory releases.
4. **Medium — Checkout shipping quote boundary:** a remote shipping implementation would currently run while Checkout holds database locks; keep it local or redesign the quote boundary before enabling Checkout.
5. **Medium — MySQL concurrency unverified for this coordinator:** H2 tests cover lock serialization and rollback shape, but not InnoDB isolation, gap locking, or deadlock handling. Run guarded MySQL tests only after the dedicated test target is approved.
