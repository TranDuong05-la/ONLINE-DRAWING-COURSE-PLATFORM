# Phase 4D-2C-2: Cart Integration and Schema Safety

## Hibernate schema policy

The main application configuration now uses `spring.jpa.hibernate.ddl-auto=validate`. Hibernate may inspect mappings and validate the deployed schema, but it will not create, alter, or drop tables. A mismatch stops startup. This is a fail-safe default for normal application profiles. No migration or database operation was run.

The `test` profile retains `create-drop` against its explicitly configured in-memory H2 database. Production deployments must not override `spring.jpa.hibernate.ddl-auto` with `update`, `create`, or `create-drop`; deployment configuration should keep `validate` (or use `none` only where schema validation is deliberately handled elsewhere). Repository search found no other checked-in DDL mode or environment override. Environment and command-line configuration can override YAML, so deployment configuration still needs review.

## Ported Cart scope

Ported D-owned Cart domain, JPA mappings, repositories, mapper, catalog/user ports, application service, state, validation request DTOs, and existing unit tests. Cart entity/repository discovery is supplied by the existing explicit dual-package bootstrap. No Order, Checkout, Voucher, Payment, catalog, user, inventory, controller, or scheduler code was imported.

`AuthenticatedCartCurrentUser` delegates to A's `AuthenticatedUserIdResolver`, which accepts only A's authenticated typed principal populated after JWT validation and persisted-user lookup. Cart service methods have no caller-supplied user ID parameter; the resolved Integer is used for owner-scoped Cart queries, mutations, and item lookup. The Cart application service remains explicitly constructed and retains its `REQUIRES_NEW` read/write semantics.

No `CartCatalog` production adapter exists because Course/Product modules are not integrated. Consequently, the Cart application service is not registered as a production bean and no Cart endpoint is active. This prevents unauthoritative item availability/ownership decisions.

## Persistence assumptions

The mappings expect signed MySQL `INT` generated IDs, `carts.user_id` unique, `cart_items` unique on `(cart_id, item_type, item_id)`, and quantity/item-type checks. There is intentionally no polymorphic Course/Product foreign key and no inventory reservation. Cart user IDs are Integer and remain unlinked by an FK until the shared User deletion/schema policy is agreed. Apply an owner-reviewed migration before enabling Cart on MySQL; `validate` will fail until the schema matches.

## Flutter-facing REST contract proposal (not implemented)

- `GET /api/cart`: authenticated owner cart; missing cart returns empty without creating a row.
- `POST /api/cart/items`: body contains `itemType`, integer `itemId`, and `quantity`; no `userId` or price. Course quantity must equal one; product quantity positive. Return updated cart.
- `PATCH /api/cart/items/{cartItemId}`: body contains positive `quantity`; only products may change quantity; course quantity remains one.
- `DELETE /api/cart/items/{cartItemId}`: remove only an item owned through the authenticated user's cart.
- `DELETE /api/cart`: clear only the authenticated user's cart.

Suggested responses: `200` for reads/updates/deletes, `201` for a newly added line (or `200` for idempotent adds), `400` invalid input, `401` unauthenticated, `404` missing/non-owned item, `409` catalog/ownership conflicts, and `503` unavailable catalog integration. Error bodies should follow A's existing error format. A controller and exact DTO/error conventions require team approval and Flutter coordination.

## Tests and boundaries

The D database-free Cart unit tests are ported alongside a principal adapter test and H2 bootstrap test. The original D disposable MySQL suite has no Cart-specific integration class; its harness covers broader D modules and was not imported. No Docker/MySQL test was run here. H2 tests verify entity/repository registration only; they do not verify MySQL CHECK enforcement, lock behavior, or schema compatibility.

Before enabling Cart: integrate trusted Course/Product `CartCatalog`, approve and apply the Cart schema through the schema owner, add MySQL Cart persistence/concurrency tests using the disposable harness, agree endpoint/error contracts, and then expose the Cart service/controller under JWT-protected routes.
