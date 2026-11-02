# D-Combo-2A — Combo Schema and Integration Contract Proposal

Status: proposal only. This document does not approve a migration, enable a runtime feature, or define Member B/C implementation as complete.

## 1. Scope and confirmed rules

This proposal adds a `COMBO` commerce item whose catalog definition contains Courses only. A Combo is bought as one immutable OrderItem and fulfilled as multiple Course claims.

The following rules are fixed for this proposal:

- A Combo contains Courses only and its cart/order quantity is exactly `1`.
- Checkout rejects the entire request if the buyer already owns any component Course.
- Checkout rejects repeated Course coverage across selected items, including Combo-to-Combo and Combo-to-standalone-Course overlap.
- There is no personalized Combo price and no credit for Courses already owned.
- A voucher applies to the Combo OrderItem, not independently to its components.
- A Combo can be refunded only as a whole; an individual component cannot be refunded.
- There is one Learner Profile per User for this integration. Member D passes authenticated `userId`; Member C resolves and validates `learnerProfileId`.

Products are not valid Combo components. Existing standalone Course and Product behavior remains unchanged.

## 2. Existing baseline and compatibility boundary

The repository currently has these relevant structures:

- V1 creates `users` and `user_profiles`. It indexes `user_profiles.user_id` but does not enforce one LEARNER profile per User.
- V2 creates polymorphic `cart_items(item_type,item_id)` with `COURSE` and `PRODUCT`, a uniqueness key on `(cart_id,item_type,item_id)`, and Course quantity `1`.
- V3 creates immutable `order_items` with the same two types, unique line reference and position, and all monetary/voucher snapshots on the OrderItem.
- V4 adds transactional voucher reservations/claims tied to the Order.
- V5–V9 add payment evidence, immutable settlement, reconciliation, and durable settlement work. V9 settlement completion must remain in the same local transaction as all participant writes.
- Checkout currently locks the Cart, validates catalog/ownership/pending state, inserts the Order, then reserves Product and Course participants in deterministic ID order in one REQUIRED transaction.
- Settlement locks Order then payment state and participants, confirms Course claims, inserts the settlement ledger, and completes V9 work atomically.
- Expiration locks the same payment evidence and releases only a clean unpaid Order's voucher, Product reservations, and Course claims atomically.
- Refund code currently models `COURSE` and `PRODUCT`; it has no persisted Combo refund scope or component aggregation contract.

No V1–V9 migration defines `COMBO`, `order_combo_components`, or `course_claims`. Those migration files must not be edited. Any accepted implementation must be a separately reviewed additive migration after V9; this document deliberately does not create or assign V10.

## 3. Proposed storage model

### 3.1 `cart_items`

Extend `item_type` to allow `COMBO`. Keep `item_id` polymorphic: for a Combo row it is Member B's stable `comboId`. Keep the existing unique key `(cart_id,item_type,item_id)` so the same Combo cannot appear twice in one Cart.

Replace the value check conceptually with:

```sql
CHECK (
    quantity > 0
    AND item_type IN ('COURSE', 'PRODUCT', 'COMBO')
    AND (item_type = 'PRODUCT' OR quantity = 1)
)
```

No Combo price, name, component list, ownership result, or catalog version belongs in the Cart row. Cart is a reference and quantity store; trusted catalog facts are re-read for display and again at Checkout. Adding an existing Combo is idempotent in the same way as the existing Course duplicate path; it must not increment quantity.

The polymorphic `item_id` cannot have a safe database FK to Member B's Combo table. Existence and activity are trusted catalog/application validations and must be repeated at Checkout.

### 3.2 `order_items`

Extend `item_type` to allow `COMBO` and require quantity `1` for both Course and Combo lines:

```sql
CHECK (
    quantity > 0
    AND item_type IN ('COURSE', 'PRODUCT', 'COMBO')
    AND (item_type = 'PRODUCT' OR quantity = 1)
)
```

For a Combo OrderItem:

- `item_id` is the stable Member B `comboId`.
- `name_snapshot` is the Combo name observed at Checkout.
- `unit_price_vnd` and `gross_amount_vnd` are the trusted Combo price, not the sum of current Course prices.
- `voucher_eligible` is decided for the Combo line by the trusted catalog/policy contract.
- `allocated_discount_vnd` is allocated once to the Combo line by the existing Order discount allocator.
- `net_amount_vnd` is the only refundable paid amount for that Combo.

Component rows must not receive price or voucher allocations. This prevents rounding ambiguity, accidental partial refunds, and later catalog changes from rewriting Order history.

Add a candidate key on `(id,order_id)` only if required by the composite component FK below. The existing `(order_id,item_type,item_id)` and `(order_id,line_position)` unique keys remain.

### 3.3 Immutable `order_combo_components`

Create an immutable child table for the exact component list accepted at Checkout. Proposed shape:

```sql
CREATE TABLE order_combo_components (
    order_item_id INT NOT NULL,
    order_id INT NOT NULL,
    component_position INT NOT NULL,
    course_id INT NOT NULL,
    course_name_snapshot TEXT NOT NULL,
    PRIMARY KEY (order_item_id, component_position),
    CONSTRAINT uk_order_combo_component_course
        UNIQUE (order_item_id, course_id),
    KEY ix_order_combo_components_order_course (order_id, course_id),
    CONSTRAINT ck_order_combo_component_values CHECK (
        component_position >= 0 AND course_id > 0
    ),
    CONSTRAINT fk_order_combo_component_item_route
        FOREIGN KEY (order_item_id, order_id)
        REFERENCES order_items (id, order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_combo_component_order
        FOREIGN KEY (order_id)
        REFERENCES orders (id) ON DELETE RESTRICT
);
```

The redundant `order_id` is intentional: it supports owner/order pending-purchase queries and proves the component is routed through the same Order as its OrderItem. If MySQL requires it, add `UNIQUE (id,order_id)` to `order_items` before the composite FK. Use the same signed `INT` family as existing Order, OrderItem, and Course identifiers.

The database cannot express all required assertions with a CHECK or FK. Application persistence must additionally require that the parent OrderItem is `COMBO`, its quantity is `1`, positions are contiguous from `0`, the component set is nonempty, and the persisted rows exactly match the trusted catalog snapshot. Insert the OrderItem and every component in the same Checkout transaction. Expose no update/delete repository operation; corrections require a separate audited design, not mutation of purchase history.

`course_name_snapshot` is recommended for stable Order/refund/audit display. Member B must confirm the authoritative display field and maximum size. If a bounded `VARCHAR` is selected instead of `TEXT`, its limit must be part of the catalog contract and tested before migration approval.

No FK from `course_id` to a B-owned table is proposed until Member B confirms table ownership, key type, deletion policy, and migration ordering. Purchase history must survive catalog retirement.

## 4. Trusted Member B Combo Catalog contract

Member B must provide a bounded local read adapter. It must not make HTTP/network calls while a Cart, Order, or settlement transaction holds database locks.

The minimum trusted result for `findComboForCart(comboId)` is:

- stable positive `comboId`;
- current display name;
- active/purchasable flag;
- nonempty, duplicate-free ordered component Course IDs;
- enough Course activity information to reject an invalid component.

The minimum trusted result for `findComboForCheckout(comboId)` is an internally consistent snapshot containing:

- stable positive `comboId` and immutable/monotonic `definitionVersion`;
- nonblank Combo name;
- nonnegative whole-VND Combo price;
- Combo active/purchasable flag;
- explicit voucher eligibility;
- a nonempty ordered list of unique component snapshots, each with positive `courseId`, nonblank Course name, and purchasable/active status;
- an integrity guarantee that all components are Courses and all fields belong to the same Combo definition version.

The adapter must fail closed on missing, malformed, mixed-version, duplicate, inactive, or non-Course components. Checkout must use only this trusted result for the Combo line price/name/components. It must not accept those values from a request or Cart DTO and must not calculate an owned-course credit or personalized price.

Recommended consistency contract: Member B returns the snapshot from the same local database and either locks/version-validates the Combo definition through Order/component insertion or guarantees immutable versioned definitions. A read followed by unrelated unlocked mutable reads is insufficient because price and components could come from different versions.

Member B should define catalog retirement semantics. A Combo may become unavailable for new Checkout without invalidating historical `order_items` or `order_combo_components`.

## 5. Member C `course_claims` contract

Member C owns Learner Profile resolution and Course claim persistence. D supplies the authenticated positive `userId`, `courseId`, `orderId`, and the relevant `orderItemId`; D never selects or trusts a client-supplied `learnerProfileId`.

C must resolve exactly one LEARNER profile for the User in the caller's local transaction. Zero or multiple matches fail closed. V1 does not currently enforce this cardinality, so C must confirm whether it will add a database invariant, use a canonical mapping, or return a typed ambiguity failure.

Required operations are local, deterministic, idempotent transaction participants:

- ownership query for a set of Course IDs by authenticated `userId`;
- active pending-claim query for a set of Course IDs at `observedAt`;
- reserve/claim all selected Courses for an Order during Checkout;
- confirm all that Order's claims during verified Settlement;
- release only that Order's unconfirmed claims on final clean expiration/cancellation;
- provide authoritative progress/assignment and claim-revocation behavior for whole-Combo refund review.

Each claim must retain enough origin to distinguish a standalone Course from a Combo component, preferably `order_id`, `order_item_id`, `course_id`, and resolved `learner_profile_id`. C must enforce one active ownership/pending route per Learner/Course under concurrency. The authoritative constraint may be a canonical claim row/state machine or an equivalent lock table, but a preceding `isOwned` read is not concurrency protection.

Batch operations are preferred. They must sort and lock by Course ID, validate that every requested claim belongs to the same User/Order route, compare exact replay payloads, and either apply all component claims or none. All mutations must join D's REQUIRED transaction and datasource/transaction manager. No remote call, async event, or after-commit best effort can satisfy Checkout, Settlement, or Expiration atomicity.

## 6. Checkout contract

After locking the owner-scoped Cart and checking its revision, Checkout must expand all selected Course coverage before creating an Order:

1. Resolve every standalone Course and Combo through trusted local catalog adapters.
2. Reject a Combo with zero components, repeated component IDs, a non-Course component, inactive Combo, or inactive/unpurchasable Course.
3. Build a map `courseId -> selected source` across standalone Course lines and all Combo components.
4. Reject on the second occurrence. This covers standalone-to-Combo, Combo-to-Combo, and malformed duplicate-in-Combo overlap. It also avoids duplicate C locks/claims later.
5. Ask C for ownership of the complete unique Course set. If any Course is owned, reject the whole Checkout; do not remove it, discount it, or partially purchase the Combo.
6. Check active pending purchase/claim state for the complete Course set at the same trusted `observedAt`. Reject if any Course is blocked.
7. Create one `COMBO` OrderItem per selected Combo, apply any voucher allocation to that line, and persist all immutable component snapshots.
8. Reserve Product inventory, standalone Course claims, and Combo component claims in the shared deterministic lock order. The write-side C operation repeats ownership/pending checks atomically.

The pending check must be extended beyond the current `order_items(item_type='COURSE')` lookup. It must recognize component Courses through `order_combo_components`, or C's authoritative pending claims must cover both origins. A split implementation must not produce contradictory answers.

Suggested typed rejections include `COMBO_UNAVAILABLE`, `COMBO_INVALID_DEFINITION`, `DUPLICATE_COURSE_COVERAGE`, `COURSE_ALREADY_OWNED`, `COURSE_PENDING_PURCHASE`, and `LEARNER_PROFILE_UNRESOLVED`. Exact public error mapping remains an API decision.

Checkout idempotency remains owner-scoped. An exact replay returns the original Order and component snapshots; it must not re-read a changed Combo definition and rewrite history. A new Checkout attempt after catalog change uses the new trusted version.

## 7. Atomic Settlement and Expiration integration

Settlement and Expiration must derive the fulfillment Course set as the union of:

- standalone `COURSE` OrderItems; and
- all `order_combo_components` belonging to `COMBO` OrderItems in that Order.

The persisted Order snapshot, not the current Combo catalog, is authoritative after Checkout. Neither path may call Member B.

Settlement retains the existing global order: Order, PaymentAttempt(s), settlement evidence/ledger, voucher, Products ascending ID, Courses ascending ID, Order confirmation/ledger, then the final V9 lease fence. C confirms every standalone and Combo component claim idempotently in that same local REQUIRED transaction. A missing, extra, mismatched, or already-owned-by-another-route claim fails the entire settlement and routes to retry/review according to the existing failure taxonomy; no subset may become owned.

Expiration keeps its current payment-evidence gates. Only after proving a clean, unpaid, due Order may it release the voucher, Product reservations, and every unconfirmed standalone/Combo Course claim in one REQUIRED transaction. It must never release claims because V9 work is delayed, exhausted, or in manual review. Settlement and expiration must use the same Order-first lock order so they cannot split a Combo outcome.

Replay invariants:

- confirmed claims remain confirmed on exact Settlement replay;
- released claims remain released on exact Expiration replay;
- Settlement versus Expiration is decided by the locked Order/payment evidence, never last-writer-wins;
- a missing component/claim or snapshot-route mismatch requires review rather than silent reconstruction from the live catalog.

## 8. Whole-Combo refund contract

A refund scope may target a Combo OrderItem only. It may not target `order_combo_components` or a subset of Course IDs. The maximum principal refund is that Combo OrderItem's immutable `net_amount_vnd`, including its allocated voucher discount. Component catalog prices and current Combo prices do not participate in the calculation.

Before approval, C must return authoritative evidence for every component from the immutable snapshot. The decision is all-or-reject: if any component is ineligible, ambiguous, missing, or has unavailable evidence, the Combo refund cannot be approved automatically. A successful refund must apply the agreed ownership/access reversal to every component atomically with the durable refund state required by the eventual refund design; partial revocation is forbidden.

The current refund model has no `COMBO` item type, persisted refund ledger, aggregate Course evidence rule, or atomic C reversal contract. Combo refund execution must therefore remain disabled until those are designed and tested.

Unresolved refund policy decisions are listed in section 11. They must not be guessed in implementation.

## 9. Proposed constraints and indexes summary

| Structure | Proposal | Purpose |
|---|---|---|
| `cart_items.ck_cart_items_values` | allow `COMBO`; require quantity `1` for Course/Combo | Reject invalid persisted quantities/types |
| `cart_items.uk_cart_items_reference` | retain `(cart_id,item_type,item_id)` | One row per Combo reference in a Cart |
| `order_items.ck_order_items_values` | allow `COMBO`; require quantity `1` for Course/Combo | Protect immutable Order lines |
| `order_items.uk_order_items_reference` | retain `(order_id,item_type,item_id)` | No duplicate Combo line by ID |
| `order_items.uk_order_items_position` | retain `(order_id,line_position)` | Stable line order |
| `order_items` candidate key | optionally `UNIQUE(id,order_id)` | Composite component route FK target |
| `order_combo_components` PK | `(order_item_id,component_position)` | Stable ordered immutable snapshot |
| `order_combo_components` unique | `(order_item_id,course_id)` | No repeated Course within one Combo line |
| `order_combo_components` index | `(order_id,course_id)` | Pending/settlement/expiration traversal |
| component route FK | `(order_item_id,order_id) -> order_items(id,order_id)` | Prevent cross-Order attachment |
| component Order FK | `order_id -> orders(id)` with `RESTRICT` | Explicit historical ownership/route |

Cross-line duplicate Course coverage and parent `item_type='COMBO'` cannot be enforced by these ordinary constraints. Checkout validation plus C's concurrent claim invariant are both required.

## 10. Migration compatibility and test plan

### Migration compatibility

- Preserve V1–V9 byte-for-byte and their Flyway checksums. Use a new reviewed migration after V9 only when B/C contracts are approved.
- Existing Cart and Order rows remain valid. The new component table begins empty; do not backfill historical Orders by consulting today's catalog.
- MySQL 8.0.16+ enforced CHECK behavior remains a prerequisite. The migration must use supported `DROP CHECK`/`ADD CONSTRAINT ... CHECK` syntax and verify actual constraint names.
- Update Java enums, Hibernate `@Check` metadata, DTO validation, persistence mappers, history projections, and tests in the same compatibility release. Reading a new `COMBO` value with old enum code will fail.
- If rolling deployment is required, use an explicit expand/contract rollout that prevents new `COMBO` rows until every reader understands them. Schema-first plus immediate writes is unsafe.
- Confirm MySQL FK signedness/type/collation and Hibernate validation for the composite candidate key. H2 success is not sufficient proof of MySQL compatibility.
- The V1 Learner cardinality gap must be resolved without rewriting V1. Any new uniqueness strategy must preserve legitimate Instructor profiles and existing data.

### Required tests

Pure/domain tests:

- Combo quantity must be exactly `1`; Products retain positive multi-quantity behavior.
- Component list is nonempty, Course-only, ordered, and duplicate-free.
- Voucher discount is allocated to the Combo OrderItem only; components have no price allocation.
- Whole-Combo refund amount equals the Combo line net amount; component/partial scopes are rejected.

Checkout integration tests:

- Same Combo add is idempotent; owner scoping and Cart revision still hold.
- Reject owned component, pending component, Combo-to-Combo overlap, Combo-to-standalone overlap, duplicate catalog component, inactive Combo, inactive Course, and unresolved/ambiguous Learner Profile.
- Concurrent Checkouts for overlapping components yield at most one active claim route.
- Order, Combo line, all component snapshots, voucher reservation, and C claims commit together or all roll back.
- Exact idempotency replay returns unchanged component snapshots after a catalog version changes.

Settlement/expiration tests:

- Settlement confirms all standalone and Combo claims and writes V8/V9 state atomically; injected failure on the last component rolls everything back.
- Expiration releases all unconfirmed component claims only for clean unpaid evidence; success/unknown/conflict/V9 manual-review evidence prevents unsafe release.
- Concurrent Settlement and Expiration obey Order-first locking and produce only fully confirmed or fully released component sets.
- Replay is idempotent; route/snapshot/claim mismatch requires review.
- Lock acquisition is deterministic across voucher, Products, and all Courses.

Refund tests:

- A component-only request is rejected.
- Any ineligible/unavailable component rejects automatic whole-Combo approval.
- Voucher allocation is not recomputed and total refunds cannot exceed the Combo line net amount.
- C reversal and durable refund state cannot split under failure or replay.

Schema tests:

- Guarded MySQL tests verify CHECK enforcement, composite FK, unique keys, index shapes, type compatibility, and Hibernate validation.
- H2 bootstrap tests cover mappings but do not substitute for MySQL behavior.
- Upgrade tests start from V1–V9 data and prove existing Course/Product Orders remain readable and unchanged.

## 11. Confirmations and blockers

### Member B confirmations required

1. Stable Combo ID SQL/Java type and authoritative local table/source.
2. Definition-version and consistent-snapshot mechanism across name, price, voucher eligibility, and components.
3. Whether Combo definitions are immutable by version or must be locked during Checkout.
4. Course-only, nonempty, unique ordered-component invariant and Course retirement behavior.
5. Authoritative Combo/Course display fields and maximum lengths.
6. Whether a safe Course FK is possible; absent confirmation, historical snapshots intentionally have no catalog FK.
7. Confirmation that price is global (not user-personalized) and that no owned-course credit is supplied.

### Member C confirmations required

1. Actual `course_claims` schema, state machine, keys, and uniqueness/concurrency invariant; none exists in V1–V9 in this repository.
2. Exact resolution rule guaranteeing one Learner Profile per User, including zero/multiple-profile handling and any new DB constraint.
3. Batch ownership/pending/reserve/confirm/release API and proof that each operation joins D's local transaction.
4. Required origin fields (`orderId`, `orderItemId`, standalone versus Combo component) and exact idempotency identity.
5. Lock order compatible with D's Order → voucher → Products → Courses order.
6. Refund evidence aggregation inputs and atomic all-component ownership/access reversal semantics.
7. Behavior if a Course is retired after Checkout but before Settlement, Expiration, or refund.

### Cross-member policy decisions still required

- For refund eligibility, whether every component independently uses the existing Course rule and which timestamp starts the seven-day window for the Combo.
- Whether any component assignment/progress makes the whole Combo ineligible, and how unavailable or conflicting evidence is routed for manual review.
- Whether a successful whole-Combo refund permanently revokes every component claim, preserves audit-only ownership history, or creates compensating claim records.
- Voucher entitlement/restoration behavior after a Combo refund and how mixed Order refunds cap aggregate amounts.
- Public Cart/Checkout/Order-history DTO representation and error codes for Combo components.
- Maximum components per Combo, needed to bound transaction duration, lock count, request size, and settlement lease sizing.

These are blockers for migration/runtime implementation, not blockers for this schema proposal.

## 12. Recommended next phase

Run a B/C contract-confirmation phase before writing a migration. Produce signed-off catalog and `course_claims` schemas, Learner resolution/cardinality rules, bounded component count, batch transaction interfaces, lock order, and whole-Combo refund policy. Then prepare one guarded implementation slice: additive schema/mappings and read-only catalog/Order snapshot tests first, followed by disabled-by-default Checkout integration, then Settlement/Expiration, and only afterward a separately approved refund implementation. No runtime Combo write should be enabled until guarded MySQL concurrency and rollback tests pass.
