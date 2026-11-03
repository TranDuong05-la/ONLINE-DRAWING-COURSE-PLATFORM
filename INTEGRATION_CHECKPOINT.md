# A–E integration checkpoint — 2026-11-04

This branch saves ongoing integration work. It is not a release or confirmation that every purchase path works.

## Included work

- A: authentication, role assignment/history, token-version invalidation, and Flutter Admin integration. Dashboard metrics remain unavailable until backed by a real endpoint.
- B/C: Course catalog, modules/lessons, Combo definitions and ordered components, Course claims, enrollment, and learning.
- D: Course-only Cart/Checkout, VNPay session and verified reconciliation foundations, settlement and expiration, Order History, vouchers, and Refund foundation. Includes guarded V13 test infrastructure and fixture corrections.
- E: Product catalog, inventory reservation, shipping/shipment management, reviews, and Flutter supply/admin screens.
- Partial Product integration: trusted catalog adapter, order-level inventory batching, explicitly configured shipping fee, atomic Order address snapshot, and general Cart/Checkout composition source.

## Current limitations

- Product Checkout is incomplete. `ArtAcademyApplication` scans `com.artacademy` and explicitly imports selected D classes; the new general Cart/Product Checkout configurations, controller, and supply adapters under `com.drawingcourse.backend` still require deliberate registration and context/API tests. Flags alone do not make these routes available.
- D Cart/Order item identifiers remain Integer while catalog Product/Course identifiers are Long. Existing checked conversion prevents silent truncation but does not support the full BIGINT range.
- Flutter's purchase flow remains Course-only. Product detail renders, but its purchase button remains disabled. Product/mixed shipping and Checkout UI is not connected.
- Combo Checkout, expected-definition-version validation, immutable Order component snapshots, component fulfillment/refund integration, and mandatory-kit checkout are unfinished. Kit-bearing Courses currently fail closed in the Course-only catalog adapters.
- No live VNPay sandbox end-to-end success is claimed. Sandbox credentials, public verified IPN delivery, database validation, and an authorized purchase demonstration remain necessary.

## Database handoff

Historical V1–V13 migration files are unchanged by this checkpoint. Additional prepared migrations, in order:

1. V14: `users.token_version` and role-history audit persistence.
2. V15: Product categories, Product stock fields and stock backfill, reservations, inventory transactions, shipments/items, and reviews.
3. V16: Combo definition version and deterministic component positions, including backfill.

These new mappings require a compatible schema before MySQL startup with `ddl-auto=validate`. Check the actual target's history first; do not assume which migrations have run. Back up and review pending migration effects before separately approving execution. MySQL DDL can commit partially. No migrations, database checks, or database writes were performed for this checkpoint.

The existing `mysql-course-v13-it` profile targets V13. It must not be used as proof of V14–V16 compatibility: current mappings include later schema fields. Prepare a suitable guarded verification path before persistent tests.

## Verification for this checkpoint

- `cd backend; mvn -q -DskipTests test-compile` — passed; no tests or migrations executed.
- `cd mobile; flutter analyze` — passed, no issues.
- Git whitespace checks — passed before commit.
- Earlier focused results reported in this worktree: 77 backend tests and 11 Flutter tests passed. They were not rerun for this checkpoint and do not establish full Product/Combo or MySQL V14–V16 readiness.

Default application commerce, payment, worker, expiration, demo seeding, and supply development-order flags remain off. Flutter `COURSE_PURCHASE_ENABLED` defaults off. Local credentials, `.env`, database dumps, logs, and generated outputs are excluded.

## Teammate checkout

Use a clean clone or a separate worktree to preserve any local edits. From an existing clone:

```powershell
git fetch origin
git worktree add --detach ../DrawingCourseApp-team-checkpoint origin/integration/member-bc-course-learning
cd ../DrawingCourseApp-team-checkpoint
git switch -c feature/<your-next-task>
```

Supply your own local environment configuration. Do not enable optional purchase/worker flags or run pending MySQL migrations just to compile the project. Coordinate schema migration and runtime activation separately.
