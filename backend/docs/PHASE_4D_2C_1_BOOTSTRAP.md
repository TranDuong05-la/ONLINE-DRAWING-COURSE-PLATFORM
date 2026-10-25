# Phase 4D-2C-1: Bootstrap and JPA Integration Preparation

## Bootstrap

`ArtAcademyApplication` remains the only production `@SpringBootApplication`. It explicitly scans A components and explicitly registers A/D entity and repository base packages. `DrawingCourseBackendComponentScan` separately scans `com.drawingcourse.backend` and excludes the exact legacy `com.drawingcourse.backend.BackendApplication` class by FQCN regex, preventing that class's `@SpringBootApplication` meta-configuration from being discovered when D sources are later ported.

This phase does not import any D entity, repository, controller, service, or configuration class. The empty D package scan is expected to register no D beans and does not fail startup. Bootstrap tests verify that A's authentication services, security filter, identity resolver, User repositories/entities and one SecurityFilterChain remain available on the isolated H2 profile.

## JPA and schema observations

- A maps `users`, `user_addresses`, and `user_profiles`. User IDs are Java `Integer` with identity generation. D maps user ownership fields as `Integer`; its review-only SQL uses MySQL `INT`. The types align at the model level, but the deployed SQL definition and signedness must be checked by the schema owner before adding foreign keys. D's proposals currently avoid an FK to users.
- `order_shipping_addresses` is an Order-owned immutable snapshot table; it is distinct from A's saved `user_addresses` table and should not be merged into it.
- `voucher_claims` is D's sole canonical voucher claim ledger. A has no competing Voucher tables.
- `checkout_outbox` is D's mapped transactional outbox proposal; A has no shared outbox.
- `checkout_inventory_claims` and `checkout_course_claims` occur only as provisional SQL proposals and have no D JPA mappings. Do not create/port them as canonical ledgers until E and C settle ownership and transaction contracts.
- D JPA entities use Integer generated IDs and explicit table names. A has no matching entity simple names beyond the shared user-related schema; D's `Order`, `Cart`, `Voucher`, and payment domain types are not duplicate A entities.
- D's UTC `Instant` converters persist UTC wall-clock values through `LocalDateTime` at MySQL `DATETIME(6)` precision. They are D-owned mappings; A's User audit fields use `LocalDateTime`. Validate the converter columns against the final SQL before D entity import.
- No custom D `PlatformTransactionManager` bean was found. D transactional adapters consume the Boot-provided manager, which should be the same manager as A JPA once one datasource is configured. Recheck this if a second datasource or transaction manager is introduced.

## Schema safety

A's production application configuration originally used Hibernate ddl-auto update. Phase 4D-2C-2 changed it to validate before Cart mappings were enabled. The test profile's create-drop applies only to in-memory H2 tests.

## Security and D current-user integration

D's inspected identity ports are all `Integer requireUserId()` methods: `CheckoutContracts.CurrentUser`, `CartCurrentUser`, and `OrderCurrentUser`. Future D-owned adapters should delegate to A's `AuthenticatedUserIdResolver`; no user ID should come from a request field, header, URL, or JWT ID claim.

D's `VnPayIpnConfiguration` is conditional, but if enabled it also declares a catch-all HTTP Basic chain. Do not import it unchanged. A's existing JWT chain and `/api/admin/**` ADMIN rule remain authoritative. Any future IPN chain must match only `/api/payment/vnpay/ipn`; do not enable it until the settlement and fulfillment invariants are complete.
