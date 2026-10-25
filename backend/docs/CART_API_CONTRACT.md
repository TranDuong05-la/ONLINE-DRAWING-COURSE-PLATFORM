# Cart API Contract Foundation

This document describes the proposed authenticated Cart API DTO/provider boundary. The controller and production service composition remain **disabled** until the real Course, ownership, Product, and stock providers are supplied. The contracts in source have no permissive or fake production implementations.

## Trusted providers

- `CourseCartCatalogProvider.findCourse(Integer courseId)` returns an optional listing with the Integer ID, display name, current `long` VND price, and active flag.
- `CourseOwnershipProvider.isOwnedBy(Integer authenticatedUserId, Integer courseId)` reads C's authoritative enrollment/ownership state.
- `ActivePendingCoursePurchaseProvider.hasActivePendingPurchase(Integer authenticatedUserId, Integer courseId, Instant observedAt)` is an owner-scoped Order-item existence query.
- `ProductCartCatalogProvider.findProduct(Integer productId)` returns an optional listing with Integer ID, display name, current `long` VND price, and active flag.
- `ProductStockAvailabilityProvider.availableQuantity(Integer productId)` returns a read-only availability count. Its contract has no reservation/deduction operation.

All IDs are positive MySQL INT-compatible Java `Integer`s. Prices are non-negative whole VND Java `long`s. Provider failures must propagate as service errors; they must not be converted into `UNAVAILABLE` or a zero price. Providers must use bounded local reads, not HTTP calls. In particular, catalog preflight happens before the Cart write transaction and row lock.

## Pending Course query

The authoritative candidate query is an owner-scoped `exists` over persisted `OrderItemJpaEntity` joined to `OrderJpaEntity`, matching `user_id`, `item_type = COURSE`, and `item_id`, with `order.status = PENDING_PAYMENT` and `payment_expires_at > :observedAt`. The comparison is strict: `paymentExpiresAt` is an exclusive deadline, so an Order is no longer an active pending purchase at the exact deadline even if an expiration worker has not yet updated its status. CANCELLED and EXPIRED Orders do not match. FAILED retries, UNKNOWN outcomes, and successful evidence awaiting Order reconciliation still block while the Order is pending and before its deadline.

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

## Proposed endpoints and DTOs

All routes derive the owner from A's verified JWT principal. No route accepts `userId` or a price.

| Method and path | Request | Result |
|---|---|---|
| `GET /api/cart` | none | `ApiResponse<CartResponse>`; missing Cart returns empty items, zero VND subtotal, `canCheckout=false` |
| `POST /api/cart/items` | `{ "itemType": "COURSE"|"PRODUCT", "itemId": Integer, "quantity": Integer }` | Updated `CartResponse`; course quantity must be 1 |
| `PATCH /api/cart/items/{cartItemId}` | `{ "quantity": Integer }` | Updated `CartResponse`; positive quantity and course quantity 1 |
| `DELETE /api/cart/items/{cartItemId}` | none | Updated `CartResponse`; foreign/missing item has the same 404 |
| `DELETE /api/cart` | none | Empty `CartResponse`; does not require catalog availability |

Cart response fields: `cartId` (nullable only when no Cart row exists), `items`, `subtotalVnd` (integer JSON number), `currency` (`VND`), `hasUnavailableItems`, `canCheckout`, and `availabilityWarnings`. Each item contains `cartItemId`, `itemType`, `itemId`, `quantity`, nullable `displayName`, `availabilityStatus`, nullable `unitPriceVnd`, nullable `lineTotalVnd`, and nullable `availabilityMessage`. Enum wire values are uppercase: `COURSE`/`PRODUCT` and `AVAILABLE`/`UNAVAILABLE`/`OUT_OF_STOCK`.

## Mutation and transaction boundaries

`CartApplicationService` keeps its existing REQUIRES_NEW read/write behavior. Add/update catalog preflight is done outside the Cart row lock; local domain quantity validation, user/cart ownership checks, row synchronization, unique constraints, and commit remain inside the Cart write transaction. Remove and clear do not call catalog providers. Checkout must not call this service expecting its writes to join a Checkout transaction; it requires its own REQUIRED transactional Cart adapter.

The existing constraints remain important: unique `carts.user_id`; unique `(cart_id, item_type, item_id)`; positive quantity; and COURSE quantity exactly one. The database uniqueness constraint remains the final duplicate-row safeguard. Concurrent Order creation is not serialized by a Cart mutation and must be handled by Checkout's purchase-claim protocol.

## Errors

- 400: malformed DTO, nonpositive IDs/quantities, or COURSE quantity other than one.
- 401: no valid authenticated principal.
- 404: Cart item missing or owned by another user, without revealing which condition occurred.
- 409: add rejected because a Course is already owned or has an active pending purchase; a new inactive/nonexistent item is rejected according to catalog policy.
- 5xx: provider/database failure; never return a fabricated empty or zero-priced response.

## Remaining decisions/integrations

1. B supplies the real Course listing lookup and confirms whether inactive Courses retain display names.
2. C supplies ownership and confirms its canonical enrollment query. D must implement/approve the Order-item query above; whether an active pending order has other blockers must remain aligned with Checkout.
3. E supplies Product listing and read-only available quantity (physical stock minus reserved stock). Checkout still owns actual reservation.
4. Team confirms response wording/localization. `canCheckout` is currently an availability-only warning and does not invoke Checkout.

No production Cart API is ready until these providers and a fail-closed composition are present. No Order, Checkout, Payment, Voucher, or fulfillment behavior is registered by this foundation.
