# Checkout idempotency and request validation design

Status: design foundation only. Checkout remains unwired; no endpoint, entity, repository, migration, reservation, or order-creation activation is introduced here.

## Existing mechanisms

- `CheckoutRequest` is a pure intent value containing `cartRevision`, selected Cart item IDs/quantities, optional voucher code, and optional shipping address. It contains no user ID, unit price, discount, or total.
- It sorts selections by Cart item ID, trims text fields, rejects empty/duplicate selections, and requires positive item IDs and quantities. Its `canonicalFingerprint()` returns a versioned SHA-256 fingerprint over normalized request intent.
- There is no durable Checkout idempotency entity, repository, adapter, or `checkout_requests` table in the integrated JPA model or Flyway V1–V5.
- `OrderPersistenceService.insertNew` joins REQUIRED propagation but inserts a new Order on every invocation. It does not supply idempotency or Checkout composition. Its owner identity comes from the authenticated principal, and its reads use REQUIRES_NEW; those reads must not participate in Checkout.
- Phase D-Cart-2 adds the server-managed nonnegative `BIGINT` Cart revision to the entity, state, and Cart response, with V6 prepared but not yet applied to MySQL. `CheckoutRequest.cartRevision` is now a nonnegative `long` matching that wire representation. Checkout is still unwired and must compare this expected value against the persisted Cart revision under its transaction lock.

## Canonical request fingerprint

`CheckoutRequest.canonicalFingerprint()` uses SHA-256 over an unambiguous, length-prefixed UTF-8 encoding with domain tag `drawing-course-checkout-request-v1`. The stored form is `v1:` plus 64 lowercase hexadecimal characters.

Inputs, in order:

1. Cart revision as a signed 64-bit integer; negative values are rejected.
2. Selection count, then each selection sorted by `cartItemId`, represented by the positive Cart item ID and requested quantity.
3. Voucher presence marker and, when present, the trimmed voucher code.
4. Shipping-address presence marker and, when present, recipient, phone, address line, locality, postal code, and country code in fixed order.

The current request constructor trims surrounding whitespace from text and sorts selections. Voucher code case is preserved: `vouchers.code` uses binary comparison, so case-folding here would make a distinct code appear equivalent. Address case and internal whitespace are preserved. A future normalization change must version the fingerprint format and must match the value used to create the Order snapshot.

The fingerprint intentionally excludes authenticated user ID (the idempotency uniqueness scope includes the owner), server catalog values, prices, totals, stock, and payment data. Never fingerprint values supplied by the client as if they were trusted prices. The idempotency key itself is the lookup key, not part of the request fingerprint.

## Proposed `checkout_requests` schema for a future V7

This is a proposal only; do not execute it or edit V1–V5.

| Column | Proposed MySQL type | Rules |
|---|---|---|
| `id` | `INT AUTO_INCREMENT` | Primary key |
| `user_id` | `INT` | Required, authenticated owner; FK to `users(id)` with `ON DELETE RESTRICT` |
| `idempotency_key` | `VARCHAR(128)` with binary collation | Required, exact-case technical identifier; validate 1–128 permitted characters at the request boundary |
| `request_fingerprint` | `VARCHAR(67)` with binary collation | Required; `v1:` plus SHA-256 hex |
| `state` | `VARCHAR(16)` with binary collation | Required; `PROCESSING` or `COMPLETED` |
| `order_id` | `INT` | Nullable while processing; completed rows reference one Order |
| `created_at`, `updated_at` | `DATETIME(6)` | Required UTC instants |
| `completed_at` | `DATETIME(6)` | Null while processing; set when completed |

Constraints: primary key on `id`; unique `(user_id, idempotency_key)`; unique nullable `order_id`; FK `order_id` to `orders(id)` with `ON DELETE RESTRICT`; CHECK that `PROCESSING` has null `order_id`/`completed_at` and `COMPLETED` has both non-null. Use InnoDB and the existing technical-reference binary-collation convention. The suggested key length and character policy are proposed limits, not existing API rules.

The Checkout idempotency migration must use `V7__checkout_idempotency.sql` after Cart V6 is reviewed and applied. V1–V5 remain unchanged. This row is D-owned; inventory and Course claim tables remain with their agreed owners and are out of scope.

## Replay and transaction behavior

1. Resolve owner only through the trusted authenticated principal. Never accept `userId` from request data.
2. Look up `(user_id, idempotency_key)` before checking the current Cart revision. If a completed row exists, compare fingerprints: equal means return its owner-scoped original Order snapshot; different means 409. A replay must not reprice, extend the deadline, or repeat Cart cleanup/reservations.
3. For a new key, check the server-generated Cart revision under the Checkout Cart lock before creating an Order. Partial Checkout is allowed: selections can name a subset of lines. Validate that each selected line belongs to this owner's Cart and that requested quantity does not exceed its persisted quantity; COURSE remains quantity one. Remove only the purchased quantities after successful assembly.
4. Create the idempotency row as `PROCESSING`, assemble Order and all same-transaction D/C/E reservation/outbox writes, then set it `COMPLETED` with the new Order ID before commit. The idempotency row, Order, shipping snapshot, Cart cleanup, voucher reservation, inventory/course claims, and required outbox event must commit or roll back together. Use one REQUIRED transaction manager; no REQUIRES_NEW participants and no remote/gateway calls inside it.
5. A transaction rollback removes its uncommitted processing row and all participating writes. A retry with the same key can safely attempt the work again.
6. If the server commits but the response is lost, retry finds the completed row and returns the original Order.
7. Concurrent first requests are serialized by `(user_id, idempotency_key)` uniqueness. At most one may create an Order. If the first commits, the other compares the stored fingerprint and replays or returns 409. If the first rolls back, another request may proceed. A database unique violation can mark its transaction rollback-only; recover by ending that transaction and performing the winner lookup in a fresh read transaction. A bounded lock wait/lock-timeout response for a still-running first request needs a consistent HTTP policy; it must never start a second Order.

Keeping `PROCESSING` and Order creation in one transaction means other transactions do not normally observe the processing row. Do not commit a separate processing row unless the team explicitly chooses leases, crash recovery, and a different atomicity model.

## Request validation gaps and assumptions

- Implemented: nonnegative numeric Cart revision; selections nonempty, unique, sorted, positive IDs and quantities; voucher/address optional and non-null option containers; surrounding text whitespace trimmed.
- Deferred until persisted Cart is available: owner-scoped line lookup, selected line exists, quantity not above current Cart quantity, COURSE quantity remains one, Cart revision equality, current catalog/stock/ownership, product-specific quantity policy, and whether a shipping address is required for selected Products.
- No maximum selection count, idempotency key character/length policy, address field lengths, country-code format, or phone format is established in source. Set bounded request limits before exposing an endpoint, aligned to API and persistence contracts. Postal code currently permits empty text; decide whether that is valid per supported shipping destinations.
- The fingerprint includes the Cart revision, so an otherwise identical request carrying a different revision is a different canonical request. For a completed key, replay/conflict comparison takes precedence over checking the latest revision.

## Remaining approval points

1. Approve idempotency key length/character rules and whether an in-flight duplicate waits for the winner or returns a retryable 409/other status after a bounded wait.
2. Apply and MySQL-verify Cart V6 through the approved isolated migration process before Checkout implementation; compare the request's numeric expected revision while holding the persisted Cart lock.
3. Confirm address and quantity validation limits with API/mobile and shipping owners.
4. Confirm whether `checkout_requests` should have an owner FK and retain rows indefinitely or under a separately designed retention policy. Do not delete rows while clients may still retry keys.
5. Confirm D/C/E transactional reservation and lock-order contracts before implementing the single-transaction Checkout assembly.
