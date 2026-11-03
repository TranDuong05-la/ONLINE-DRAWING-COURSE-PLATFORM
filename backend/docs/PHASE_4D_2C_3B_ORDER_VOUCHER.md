# Phase 4D-2C-3B: Order and Voucher Persistence Integration

## Imported slice

This phase imports D's immutable Order domain and persistence mappings, Order item and separate Order-owned shipping address snapshots, UTC converter, repositories, mapper, stored result records, persistence service, and the pure `CheckoutRequest` value type needed by the address entity. It also imports Voucher definition, per-user usage and canonical `voucher_claims` JPA mappings, repositories, domain values, mapper, and UTC converter.

`AuthenticatedOrderCurrentUser` delegates to A's `AuthenticatedUserIdResolver.requireUserId()`. There is no request-supplied owner parameter. `OrderPersistenceService` retains its existing transaction behavior: owner-scoped reads use read-only `REQUIRES_NEW`/`REPEATABLE_READ`; insertion uses `REQUIRED`. It is deliberately not registered as a Spring bean or exposed through a controller. These REQUIRES_NEW reads must not be used as Checkout transaction participants.

The voucher claim entity and repository are mapped for schema readiness, but no claim writer/configuration was imported. Voucher reserve, redeem and release mutations therefore remain unavailable. No Payment entities, Checkout orchestration, Order expiration, inventory, outbox dispatch, VNPay controller/configuration, scheduler, or fulfillment code was imported. `PaymentStatus` is included only as the persisted Order enum dependency.

## Bootstrap and schema

`ArtAcademyApplication` remains the only production application entry point. Existing explicit entity and repository scans register the selected D mappings. The D `BackendApplication`, `VoucherClaimConfiguration`, and `VnPayIpnConfiguration` were not imported. The H2 bootstrap test checks mapped entities/repositories, the identity adapter, and absence of Order persistence bean activation and Checkout/Payment/VNPay bean names.

The mappings expect the reviewed Order and Voucher tables/columns, Integer IDs, BIGINT VND/version counters, string enums, named unique constraints, and DATETIME(6) UTC-converted instants. No production SQL proposal or migration was changed or run. Main application `ddl-auto=validate` will fail startup against a database without the tables; schema-owner migration approval is required before deploying these mappings to a real database.

The schema intentionally has no `users` foreign keys for Order, usage, or claim owner IDs. The Integer `user_id` values match A's `users.id`, but user deletion/retention policy remains for the schema owner. Voucher code and claim-key collations are declared in SQL rather than JPA; verify those behaviors against MySQL. The existing proposal keeps `voucher_claims` as the only voucher claim ledger. Provisional Checkout course/inventory claim tables remain out of scope.

## Verification

Maven compile and the full isolated H2 suite passed: 119 tests, 0 failures, 0 errors, 0 skipped. Tests include Order restoration without repricing, monetary/deadline rules, owner isolation, adapter identity, persisted shipping snapshots, Voucher definition/usage mapping, optimistic version rejection, rollback, and bootstrap scanning. H2 does not verify MySQL collation, CHECK enforcement, UTC conversion under a Ho Chi Minh JVM, or InnoDB lock behavior.

The existing D MySQL harness is guarded by an explicit opt-in property and provisions a new labeled, loopback-only MySQL container with no external database fallback. It could not be run: Docker CLI reported that the local daemon pipe was unavailable. No container or database connection was attempted. Re-run the Order/Voucher-specific MySQL checks only when the local disposable Docker daemon is available; use only the dedicated test fixture, schema validation, and a test-only reviewed schema.

## Remaining integration dependencies

- **B:** Trusted Course catalog pricing/availability is required before Checkout can create course Order snapshots.
- **C:** Course ownership and pending-purchase claims are needed to reject duplicate purchases and later create enrollment after payment.
- **E:** Trusted Product pricing/availability, transactional inventory reserve/release, shipping quote, and physical fulfillment remain required for physical Orders.
- **D follow-up:** Voucher claim mutation must be integrated with Checkout and Payment only after the Payment repositories/settlement flow and shared lock order are wired. Keep the current claim configuration excluded; it currently requires Payment repositories.

No public Order or Voucher endpoint is active in this phase.
