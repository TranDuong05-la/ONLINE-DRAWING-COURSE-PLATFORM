# Phase 4D-2B Authentication Integration Contract

## Trusted identity

A's `AuthenticatedUserPrincipal` is built by `CustomUserDetailsService` from the persisted `User` row after JWT subject extraction and token validation. It exposes the persisted positive `Integer userId`, keeps the normalized database email as `getUsername()`, and keeps the existing `ROLE_<Role>` authority. The JWT contains the existing email subject and token-type claim; no client-supplied or token-supplied user ID is used for ownership.

`AuthenticatedUserIdResolver.requireUserId()` reads Spring Security's current `Authentication`, requires an authenticated non-anonymous `AuthenticatedUserPrincipal`, and otherwise fails closed with `AuthenticationCredentialsNotFoundException`.

The inspected D ports all require the same zero-argument identity operation:

- `CheckoutContracts.CurrentUser.requireUserId(): Integer`
- `CartCurrentUser.requireUserId(): Integer`
- `OrderCurrentUser.requireUserId(): Integer`

When D modules are imported, D-owned adapters should implement these interfaces by delegating to `AuthenticatedUserIdResolver`. They must not accept user IDs from request DTOs, URL parameters, headers, or unverified JWT claims. No D interface or persistence module is imported in this phase.

## Security chains

A's current chain remains the application chain: the existing authentication endpoints and Swagger paths remain explicitly public, `/api/admin/**` remains `hasRole("ADMIN")`, and all other routes remain authenticated through the JWT filter. STAFF is not ADMIN under the current policy.

D's conditional IPN configuration must not be imported as-is: its second catch-all chain uses HTTP Basic and would conflict with A's JWT chain. If a future phase needs IPN, add only a highest-priority chain whose `securityMatcher` is exactly `/api/payment/vnpay/ipn`; keep all other paths in A's JWT chain. Do not add broad permit-all matchers. The IPN route remains disabled and signed-payload verification remains an application responsibility.

## Test boundaries

`SecurityAuthorizationIntegrationTest` uses the `test` profile's in-memory H2 database and a test-only identity probe endpoint. The probe is not production code. JWT/unit tests use deterministic test-only keys. Google tokeninfo fallback is behind `GoogleTokenInfoClient`, allowing tests to mock transport while production retains the same endpoint call and response validation.