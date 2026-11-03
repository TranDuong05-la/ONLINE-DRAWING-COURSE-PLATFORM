# Durable Checkout idempotency foundation

Flyway V7 adds `checkout_requests`, uniquely scoped by `(user_id, idempotency_key)`. Keys and the versioned request fingerprint use binary comparison so technical identifiers are case-sensitive. A completed row points to one Order; processing/completed state consistency is checked by the database. Timestamps use the existing UTC `DATETIME(6)` converter.

`CheckoutIdempotencyService` is intentionally an unwired persistence primitive. It has no controller, Cart, catalog, Course ownership, Product stock, inventory, voucher reservation, or payment dependency. A future orchestrator must call it inside the same REQUIRED transaction that creates the Order and all other checkout persistence. The service rejects calls without an active transaction, preventing a processing claim from being committed independently by accident.

Claim outcomes:

- `Claimed`: the caller owns a new processing row in its current transaction.
- `Replayed`: the same owner/key/fingerprint is completed; the original owner-scoped Order ID is returned. The orchestrator must perform this lookup before comparing the current Cart revision.
- `FingerprintConflict`: the key was used for different canonical request content.
- `InProgress`: the same request is already processing and should be retried or reported as a conflict.
- `ConcurrentClaimException`: a competing transaction won the unique-key insert. The current transaction must roll back; a retry then observes the winning row.

The canonical `CheckoutRequest` fingerprint includes its Cart revision, selected Cart item IDs and quantities, normalized voucher code, and normalized address fields. It contains no client prices. A replay must submit the original canonical request; changing its revision changes the fingerprint and is correctly a conflict. The idempotency service does not read current Cart state.

V7 has not been applied or verified against MySQL in this phase. Hibernate H2 tests exercise persistence, uniqueness, replay, owner scoping, concurrency, and transaction rollback only.
