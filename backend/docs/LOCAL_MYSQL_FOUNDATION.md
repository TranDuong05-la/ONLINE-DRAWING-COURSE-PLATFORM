# Local MySQL foundation (verified)

Five ordered Flyway migrations define the currently integrated Auth + Cart + Order + Voucher + Payment model. V1–V5 were applied to `drawing_course_dev`; MySQL compatibility was separately verified against the disposable `drawing_course_it` database. Never rerun these migrations manually or against an existing/shared database.

## Migration order

1. `V1__auth_and_profiles.sql`: users, user_profiles, user_addresses.
2. `V2__cart.sql`: carts, cart_items.
3. `V3__orders.sql`: orders, order_items, order_shipping_addresses.
4. `V4__vouchers.sql`: vouchers, voucher_user_usage, canonical voucher_claims.
5. `V5__payment_audit.sql`: payment_attempts, payment_events, payment_event_conflicts.

No Course, Product, Enrollment, Inventory, Checkout, payment_transactions, Refund, Shipment, or outbox tables are included. `voucher_claims` remains the sole voucher claim ledger; payment retries are separate rows in payment_attempts.

## Auth enum storage

Hibernate 7 generated native enum columns for Auth `@Enumerated(EnumType.STRING)` mappings in the H2 bootstrap schema. D commerce fields explicitly request VARCHAR. Auth `Role`, `ProfileType`, and `AgeGroup` now also use `@JdbcTypeCode(SqlTypes.VARCHAR)`. The Java values and JSON enum names are unchanged. A new H2 metadata test checks that the generated Auth columns are JDBC VARCHAR. Migrations keep VARCHAR and add checks for the existing enum literals.

## Collation policy

- Email is normalized by existing Auth code using trim/lowercase, and its unique column uses `utf8mb4_0900_as_ci` (case-insensitive, accent-sensitive) to protect uniqueness across incoming case variants.
- Technical identifiers use binary comparison to match Java exact string equality: voucher codes, claim keys, provider/merchant/transaction/event references, fingerprints, verification references, currencies, enum strings, and Google subject IDs.
- Voucher code is only stripped, not case-normalized; lookup uses exact String equality. No Java-side normalization was added.
- No `order_code` exists in the integrated Order entity, so no order-code column/index is proposed.
- MySQL 8.0.46 verified the selected email and technical-reference collations, including case-insensitive/accent-sensitive email uniqueness and exact case-sensitive technical references.

## Time contract

D stores each `Instant` by converting it explicitly to UTC `LocalDateTime` and writes that zone-free value to `DATETIME(6)`. Reading converts that value back as UTC. Converters reject sub-microsecond precision loss; Cart lifecycle timestamps are normalized to microseconds before persistence. No `hibernate.jdbc.time_zone` override is needed for these converted values; `DATETIME` itself does not apply a timezone conversion. Auth `LocalDateTime` creation/update columns are zone-free application timestamps and are not UTC Instants. No database-side timestamp defaults are relied on.

H2/unit tests verify converter UTC values and precision rejection. The isolated MySQL suite verified the actual JPA timestamp mappings and `DATETIME(6)` round trips under `Asia/Ho_Chi_Minh`.

## Local profile behavior

- Base `application.yaml` keeps `ddl-auto: validate` and Flyway disabled.
- `application-mysql-local.yml` enables Flyway only under the explicit `mysql-local` profile, sets `clean-disabled: true`, `baseline-on-migrate: false`, validates migrations, and retains Hibernate `validate`.
- URL/user/password remain required external `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; JWT remains external `JWT_SECRET`.
- Flyway does not create the database/server. The selected database must be separately provisioned and approved before running.
- Never start this profile with a shared/production URL. Flyway runs pending migrations at application startup when this profile is active.
- The existing H2 test profile is unchanged; base Flyway is disabled unless `mysql-local` is explicitly selected.

## Known schema differences and remaining checks

Migrations follow current integrated entities, not the full conceptual DBML: Auth has `full_name`, `avatar_url`, `google_id`; saved addresses are structured; Order money uses whole-VND BIGINT and immutable item/address/deadline snapshots; item type is COURSE/PRODUCT; payment attempts/events replace the DBML payment_transactions shape. Scalar owner IDs receive database foreign keys in the migration even where JPA represents them as scalar fields.

MySQL 8.0.46 compatibility was verified with Hibernate `ddl-auto=validate` and the isolated integration suite. The suite checked foreign keys, unique and enforced CHECK constraints, whole-VND and enum column types, selected collations, and UTC `DATETIME(6)` round trips under `Asia/Ho_Chi_Minh`. MySQL 8.0.16+ is required for enforced CHECK constraints.
