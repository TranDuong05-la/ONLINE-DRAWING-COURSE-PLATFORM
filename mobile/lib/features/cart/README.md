# Course-only Cart and Checkout

The authenticated mobile Cart uses the backend Course-only Cart contract at `/api/cart` and `/api/cart/items`. Current Course names and prices come from the backend projection; the app sends only Course or Combo IDs and quantity `1`. A Combo is one cart line (`itemType: COMBO`) that grants every Course inside it; its price is decided by the server and it never takes a voucher.

Checkout posts the Cart revision, selected Cart item IDs, and optional voucher code to `/api/checkout/course-only` with an `Idempotency-Key`. It then uses the persisted Order snapshot and `/api/orders/{id}/payment-session`. VNPay browser return is never treated as payment confirmation; the client rereads Order History and Course ownership from the backend.

The Flutter screens are independently gated by `COURSE_PURCHASE_ENABLED` (default `false`). A demo build opts in with `--dart-define=COURSE_PURCHASE_ENABLED=true`; backend Cart, Course-only Checkout, and VNPay session flags must also be explicitly enabled in the server configuration. Product and mixed-item purchases remain unsupported. A Combo whose Courses include a physical gift is shown but cannot be added until gift delivery exists. `MockCartRepository` remains available only for isolated widget tests.

Run checks from `mobile/`:

```powershell
flutter analyze
flutter test
```
