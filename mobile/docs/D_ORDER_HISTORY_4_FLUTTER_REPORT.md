# D-OrderHistory-4 — Flutter Order History Integration

## Outcome

The Flutter application now has a complete authenticated Order History list and detail flow backed by the existing read-only backend endpoints:

- `GET /api/orders?page={page}&size=10`
- `GET /api/orders/{orderId}`

The feature reuses the application's existing `ApiClient`, secure token storage, refresh-token interceptor, configurable API base URL, `AuthProvider`, and `GoRouter`. It does not send `userId`, hardcode credentials, duplicate networking/authentication infrastructure, recalculate historical totals, or query current Course/Product data.

The repository already contained the core Order History models, screens, repository, router/profile entry points, and tests. This phase verified that foundation and aligned it with the final backend contract: page size 10, maximum size 50, pull-to-refresh even for an empty history, and conditional deadline display.

No backend file, migration, Checkout flow, Payment flow, Combo persistence, cancellation action, or refund action was changed.

## Existing integration reused

- `ApiClient` supplies the configured base URL, attaches the stored Bearer token, attempts the established refresh flow after HTTP 401, and maps HTTP 401/404 into the app's typed exceptions.
- `ApiOrderHistoryRepository` uses `ApiConstants.orders` and `ApiConstants.orderDetail` and passes only `page` and `size` for list requests.
- `AppRouter` protects `/orders` and `/orders/:orderId` with the existing authentication redirect.
- `ProfileScreen` already exposes an Order History navigation entry.
- Failed token refresh is synchronized through `AuthProvider.logout`; network and 404 failures do not log the user out.

## User experience

### Order History list

- Displays Order ID, creation date, Order status, payment status, final persisted VND total, and persisted item count.
- Shows initial loading, empty, and retryable error states.
- Loads pages in bounded groups of 10 and provides explicit load-more behavior.
- Deduplicates Order IDs when appending a later page.
- Prevents concurrent duplicate load-more requests.
- Supports pull-to-refresh for populated and empty histories.
- Opens `/orders/{orderId}` when an Order is selected.

### Order detail

- Displays Order ID, creation date, Order status, payment status, persisted item names, quantities, unit prices, gross amounts, allocated discounts, and net amounts.
- Displays persisted merchandise subtotal, eligible subtotal, Order discount, shipping fee, and final total without recomputation.
- Displays voucher and shipping-address snapshots only when supplied by the backend.
- Displays the payment deadline only when supplied by the backend.
- Displays a non-disclosing unavailable state for HTTP 404.
- Contains no Checkout, payment, cancellation, or refund action.

## Exact files changed

- `mobile/lib/features/order_history/data/api_order_history_repository.dart`
- `mobile/lib/features/order_history/data/order_history_repository.dart`
- `mobile/lib/features/order_history/models/order_history_models.dart`
- `mobile/lib/features/order_history/order_detail_screen.dart`
- `mobile/lib/features/order_history/order_history_screen.dart`
- `mobile/lib/features/order_history/state/order_history_controller.dart`
- `mobile/test/features/order_history/order_history_controller_test.dart`
- `mobile/test/features/order_history/order_history_navigation_test.dart`
- `mobile/test/features/order_history/order_history_repository_test.dart`
- `mobile/test/features/order_history/order_history_test_data.dart`
- `mobile/docs/D_ORDER_HISTORY_4_FLUTTER_REPORT.md`

The existing router, API constants, shared API client, token storage, authentication provider, profile navigation entry, model parsing tests, and formatting utilities required no changes.

## Test coverage

The Order History tests use fake repositories and mock API envelopes. No live backend is required.

Covered behavior includes:

- strict JSON parsing for IDs, statuses, UTC timestamps, VND integers, page metadata, and immutable snapshots;
- rejection of malformed responses, unsupported enums, fractional money, invalid Course quantity, and page sizes above 50;
- default `page=0&size=10` request parameters with no ownership `userId` parameter;
- pagination, load-more deduplication, and concurrent load-more suppression;
- empty state, network error state, retry, and pull-to-refresh;
- list-to-detail navigation;
- persisted monetary and item-discount values;
- optional voucher, shipping address, and payment deadline behavior;
- authenticated route guard, unrecoverable 401 logout synchronization, and non-disclosing 404 detail handling.

## Verification results

Focused feature suite:

```powershell
flutter test test/features/order_history
```

Result: **23 tests passed**.

Static analysis:

```powershell
flutter analyze
```

Result: **No issues found**.

Full Flutter suite:

```powershell
flutter test
```

Result: **38 tests passed**.

All automated Flutter tests were local/mock-based. No live backend, payment provider, database, or persistent H2 instance was accessed.

## Remaining integration considerations

- A device/emulator smoke test still requires a running verified backend and a reachable API base URL. Android emulator defaults to `http://10.0.2.2:8080`; web and desktop default to `http://localhost:8080`, with the existing server configuration UI available for another host.
- The user must have a valid authenticated session; expired sessions rely on the existing refresh endpoint and logout behavior.
- The feature intentionally reflects persisted historical snapshots. Later catalog name, price, availability, or ownership changes will not alter displayed Order history.
- Current persisted backend item IDs and types remain Integer COURSE/PRODUCT snapshots. Combo history remains unavailable until backend Combo persistence is implemented separately.
- Dependency resolution reported newer incompatible package versions, but the locked dependency set analyzed and tested successfully; no package upgrade was performed.

There are no code-level blockers for Flutter integration with the verified Order History API.
