# Cart demo feature

This feature is integrated into ArtNest as an explicitly labeled, mock-only Cart preview. Open it from the cart icon in the Store tab. The existing GoRouter authentication redirect protects `/cart`.

`MockCartRepository` keeps sample data in memory. It does not use Dio, TokenStorage, backend persistence, catalog data, stock reservation, Checkout, or payment. The Checkout action only explains that ordering is unavailable; it cannot submit an Order or initiate payment.

The feature uses only Flutter Material and existing dependencies. Keep A's `main.dart`, `MaterialApp.router`, auth providers, router structure, and five-tab IndexedStack. Replace the mock repository only after authenticated Cart APIs and trusted current catalog data are available.

Run checks from `mobile/`:

```powershell
flutter analyze
flutter test
```