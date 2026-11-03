# Course-only Cart and Checkout

The authenticated mobile Cart uses the backend Course-only Cart contract at `/api/cart` and `/api/cart/items`. Current Course names and prices come from the backend projection; the app sends only Course IDs and quantity `1`.

Checkout posts the Cart revision, selected Cart item IDs, and optional voucher code to `/api/checkout/course-only` with an `Idempotency-Key`. It then uses the persisted Order snapshot and `/api/orders/{id}/payment-session`. VNPay browser return is never treated as payment confirmation; the client rereads Order History and Course ownership from the backend.

The Flutter screens are independently gated by `COURSE_PURCHASE_ENABLED` (default `false`). A demo build opts in with `--dart-define=COURSE_PURCHASE_ENABLED=true`; backend Cart, Course-only Checkout, and VNPay session flags must also be explicitly enabled in the server configuration. Product, mixed-item, and Combo purchases remain unsupported. `MockCartRepository` remains available only for isolated widget tests.

Run checks from `mobile/`:

```powershell
flutter analyze
flutter test
```
