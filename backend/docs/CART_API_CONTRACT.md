# Cart API Contract Foundation

This document describes the authenticated Cart API DTO/provider boundary. The controller routes are implemented but conditional on `app.cart.api-enabled=true`; the normal application configuration does not set this property. Production Cart service/projector composition remains absent until the real Course, ownership, Product, and stock providers are supplied. The contracts in source have no permissive or fake production implementations.

## Trusted providers

- `CourseCartCatalogProvider.findCourse(Integer courseId)` returns an optional listing with the Integer ID, display name, current `long` VND price, and active flag.
- `CourseOwnershipProvider.isOwnedBy(Integer authenticatedUserId, Integer courseId)` reads C's authoritative enrollment/ownership state.
- `ActivePendingCoursePurchaseProvider.hasActivePendingPurchase(Integer authenticatedUserId, Integer courseId, Instant observedAt)` is an owner-scoped Order-item existence query.
- `ProductCartCatalogProvider.findProduct(Integer productId)` returns an optional listing with Integer ID, display name, current `long` VND price, and active flag.
- `ProductStockAvailabilityProvider.availableQuantity(Integer productId)` returns a read-only availability count. Its contract has no reservation/deduction operation.

All IDs are positive MySQL INT-compatible Java `Integer`s. Prices are non-negative whole VND Java `long`s. Provider failures must propagate as service errors; they must not be converted into `UNAVAILABLE` or a zero price. Adapters should raise `CartCatalogUnavailableException` for dependency outages so the Cart API can return 503; invalid provider data and other unexpected failures remain errors. Providers must use bounded local reads, not HTTP calls. Catalog preflight happens before the Cart write transaction and row lock. The display projector runs after each REQUIRES_NEW Cart read/write has completed; provider reads must not run while a Cart row lock is held.

### Required team integration contract

| Owner | Existing port | Required implementation behavior | Missing/item-state behavior |
|---|---|---|---|
| B — Course | `CourseCartCatalogProvider.findCourse(Integer courseId)` | Read the canonical Course locally; return Integer ID, display name, current whole-VND `long` price, and active/purchasable state. Confirm whether inactive Courses can still supply a display name. | Missing returns `Optional.empty`; inactive is explicit and cannot be newly added. Never provide a client-supplied or stale-as-current price. |
| C — Course ownership | `CourseOwnershipProvider.isOwnedBy(Integer authenticatedUserId, Integer courseId)` | Query canonical ownership/enrollment state using the authenticated Integer user ID. Define how revoked or transferred enrollment affects ownership. | Owned Courses cannot be newly added and an existing line projects as `UNAVAILABLE`. Query failures are errors, never `false`. |
| D — Pending Course Order | `ActivePendingCoursePurchaseProvider.hasActivePendingPurchase(...)` | Already implemented by `JpaActivePendingCoursePurchaseProvider` with an owner-scoped Order/OrderItem existence query. | `PENDING_PAYMENT` with `paymentExpiresAt > observedAt` blocks. Equal/null deadlines and non-pending statuses do not. Payment-attempt status does not release the block. |
| E — Product | `ProductCartCatalogProvider.findProduct(Integer productId)` | Read canonical Product locally; return Integer ID, display name, current whole-VND `long` price, and active/purchasable state. | Missing/inactive Products are rejected for new additions and projected as `UNAVAILABLE` without a price. |
| E — Stock | `ProductStockAvailabilityProvider.availableQuantity(Integer productId)` | Return a nonnegative, read-only quantity according to E's canonical inventory model. Document whether this is physical minus reserved stock and the freshness/transaction guarantees. | Fewer units than Cart quantity projects `OUT_OF_STOCK`; retain the item and current price, exclude it from subtotal, and set `canCheckout=false`. Cart never reserves stock; Checkout performs the atomic reservation. |

Each provider must distinguish normal missing/inactive results from infrastructure failure. Course/Product and ownership/stock reads should be bounded local calls. Checkout must independently revalidate current price, ownership/pending purchase, eligibility, and stock before creating reservations.

## Pending Course query

The authoritative candidate query is an owner-scoped `exists` over persisted `OrderItemJpaEntity` joined to `OrderJpaEntity`, matching `user_id`, `item_type = COURSE`, and `item_id`, with `order.status = PENDING_PAYMENT` and `payment_expires_at > :observedAt`. The comparison is strict: `paymentExpiresAt` is an exclusive deadline, so an Order is no longer an active pending purchase at the exact deadline even if an expiration worker has not yet updated its status. CANCELLED and EXPIRED Orders do not match. FAILED retries, UNKNOWN outcomes, and successful evidence awaiting Order reconciliation still block while the Order is pending and before its deadline. This query is implemented by `JpaActivePendingCoursePurchaseProvider`; it does not query Payment status.

This read is not a concurrency claim. A race can create an Order after the Cart preflight. Checkout must repeat ownership and pending-purchase checks as part of its own atomic course-purchase claim before Order creation; Cart alone cannot provide that guarantee.

## Availability projection

`CartAvailabilityProjector` consumes a persisted `CartState` and live trusted providers. It is not a Spring bean. It does not modify the Cart or reserve stock.

- AVAILABLE lines require current trusted price and line total.
- UNAVAILABLE lines remain in the response; their price and line total are null so stale/untrusted values are never presented as current.
- OUT_OF_STOCK lines remain in the response with current catalog price when available. Their line total is informational, and they are excluded from the Cart subtotal.
- `subtotalVnd` sums only AVAILABLE lines with overflow-checked integer arithmetic.
- `hasUnavailableItems` is true for UNAVAILABLE or OUT_OF_STOCK lines.
- `canCheckout` is false for an empty Cart or if any item is not AVAILABLE. This is an availability warning only, not Checkout activation or a payable quote.
- Provider/database outages are errors, not an availability status.
- An active Product can be added even when its current stock is insufficient; it remains visible as OUT_OF_STOCK and the availability flag blocks Checkout. Inactive or nonexistent catalog items are rejected for new additions.

## Conditional endpoints and DTOs

All routes derive the owner from A's verified JWT principal. No route accepts `userId` or a price.

| Method and path | Request | Result |
|---|---|---|
| `GET /api/cart` | none | `ApiResponse<CartResponse>`; missing Cart returns empty items, zero VND subtotal, `canCheckout=false` |
| `POST /api/cart/items` | `{ "itemType": "COURSE"|"PRODUCT", "itemId": Integer, "quantity": Integer }` | Updated `CartResponse`; course quantity must be 1 |
| `PATCH /api/cart/items/{cartItemId}` | `{ "quantity": Integer }` | Updated `CartResponse`; positive quantity and course quantity 1 |
| `DELETE /api/cart/items/{cartItemId}` | none | Updated `CartResponse`; foreign/missing item has the same 404 |
| `DELETE /api/cart` | none | Empty `CartResponse`; does not require catalog availability |

Cart response fields: `cartId` (nullable only when no Cart row exists), `revision` (server-owned nonnegative integer; 0 for a Cart not yet persisted), `items`, `subtotalVnd` (integer JSON number), `currency` (`VND`), `hasUnavailableItems`, `canCheckout`, and `availabilityWarnings`. Each item contains `cartItemId`, `itemType`, `itemId`, `quantity`, nullable `displayName`, `availabilityStatus`, nullable `unitPriceVnd`, nullable `lineTotalVnd`, and nullable `availabilityMessage`. Enum wire values are uppercase: `COURSE`/`PRODUCT` and `AVAILABLE`/`UNAVAILABLE`/`OUT_OF_STOCK`.

## Mutation and transaction boundaries

`CartApplicationService` keeps its existing REQUIRES_NEW read/write behavior. Add/update catalog preflight is done outside the Cart row lock; local domain quantity validation, user/cart ownership checks, row synchronization, unique constraints, and commit remain inside the Cart write transaction. Remove and clear do not call catalog providers. Checkout must not call this service expecting its writes to join a Checkout transaction; it requires its own REQUIRED transactional Cart adapter.

The existing constraints remain important: unique `carts.user_id`; unique `(cart_id, item_type, item_id)`; positive quantity; and COURSE quantity exactly one. V6 adds `carts.revision BIGINT NOT NULL DEFAULT 0` and a nonnegative CHECK; existing rows start at zero. Each successful contents-changing mutation advances it exactly once while holding the existing pessimistic Cart row lock, in the same transaction as item changes. No-op duplicate COURSE add, same-quantity update, and clear of an empty Cart do not advance it. Missing Cart reads/clear responses report revision 0. Checkout compares a supplied expected revision against the persisted value inside its own transaction; it must check a completed idempotency replay first. The database uniqueness constraint remains the final duplicate-row safeguard. Concurrent Order creation is not serialized by a Cart mutation and must be handled by Checkout's purchase-claim protocol.

## Errors

- 400: malformed DTO, nonpositive IDs/quantities, or COURSE quantity other than one.
- 401: no valid authenticated principal.
- 404: Cart item missing or owned by another user, without revealing which condition occurred.
- 409: add rejected because a Course is already owned or has an active pending purchase; a new inactive/nonexistent item is rejected according to catalog policy.
- 503: required catalog/availability dependency is unavailable; never return a fabricated empty or zero-priced response.
- Other provider/database failures remain errors and are never translated into unavailable items or a successful response.

## Remaining decisions/integrations

1. B supplies the real Course listing lookup and confirms whether inactive Courses retain display names.
2. C supplies ownership and confirms its canonical enrollment query and semantics for revoked/transferred enrollments.
3. E supplies Product listing and read-only available quantity, including its stock formula and consistency guarantees. Checkout still owns actual reservation.
4. A production composition must adapt these ports into `CartCatalog<Integer,Integer>` and `CartAvailabilityProjector`, map ineligible additions to Cart conflicts, and map provider outages to `CartCatalogUnavailableException`. Do not register a permissive default.
5. Team confirms response wording/localization. `canCheckout` is currently an availability-only warning and does not invoke Checkout.

No production Cart API is ready until these providers and a fail-closed composition are present. No Order, Checkout, Payment, Voucher, or fulfillment behavior is registered by this foundation.
