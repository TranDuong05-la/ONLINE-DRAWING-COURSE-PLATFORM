# D Payment 3A: VNPay IPN Boundary and Reconciliation Design

**Status:** D-Payment-3D adds a gated HTTP boundary. `VNPAY_IPN_ENABLED` defaults to false, so the route is not exposed in default application configuration. User-provided pre-phase results were 325/325 default H2 and 35/35 guarded MySQL tests; this phase's verification is recorded separately below.

## Existing implementation inventory

| Component | Verified source behavior | Reuse and limitation |
|---|---|---|
| `VnPayCallbackVerifier` / `VerifiedVnPayCallback` | Verifies the configured TMN code and HMAC-SHA512 over canonical signed `vnp_` fields. Requires singular `vnp_TmnCode`, `vnp_Amount`, `vnp_BankCode`, `vnp_OrderInfo`, `vnp_TransactionNo`, `vnp_ResponseCode`, `vnp_TransactionStatus`, `vnp_TxnRef`, and `vnp_SecureHash`; rejects repeated `vnp_` values. `vnp_PayDate` is optional at signature-verification time. Only the verifier constructs the immutable verified callback. | Reuse at the HTTP boundary. Bind request parameters as `Map<String,List<String>>`; do not collapse repeated values before verification. Authentication proves callback integrity, not that the payment matches the local Order or is eligible for settlement. |
| `VnPayIpnReconciliationService.reconcile(VerifiedVnPayCallback)` | Re-verifies the callback, then uses a REQUIRED `TransactionTemplate`; for known references it locks Order then PaymentAttempt, applies V5 event/conflict and V8 case writes together, and never updates Order or fulfills. Unknown merchant references get an unlinked V8 case. | Reuse; case insert failure rolls back local attempt/event changes. A unique-case race is retried only after the failed transaction rolls back and only when no caller-owned transaction is active. |
| `VnPayPaymentStatusClassifier` | Pure allowlist classifier over signed `vnp_ResponseCode` and `vnp_TransactionStatus`. Strict `00/00` is success; code `07` is suspicious/UNKNOWN; only documented failure codes paired with transaction status `02` become FAILED; all other combinations become UNKNOWN. | Reuse as conservative local policy. Public documentation does not establish the complete response/status cross-product for every account/version. |
| V5 payment persistence | `payment_attempts` has unique `(provider, merchant_reference)` and `(provider, transaction_reference)`; `payment_events` has unique `(provider,event_reference)` and required attempt/order/user FKs; conflicts reference a known first event, Attempt, Order and user. | These constraints protect routed records but intentionally cannot store an unknown merchant reference as a PaymentEvent. They make the V8 unlinked case route necessary. |
| V8 settlement/reconciliation persistence | `payment_settlements` has one row per Order, Attempt and Event, with composite route FKs. `payment_reconciliation_cases` supports an unlinked `UNKNOWN_MERCHANT_REFERENCE` case and known linked exceptional cases, with bounded case types, status/resolution fields, optional routing IDs, evidence fingerprint, optional SHA-256 payload digest and callback facts. | The reconciliation service routes unknown-reference and exceptional verified evidence into V8 using its unique identity. The gated HTTP controller calls that service only; it does not create settlement records. |
| `SettlementCoordinator.settle(IpnReconciliationResult)` | Runs through its transaction template; reloads persisted event, locks Order then Attempt, checks settlement ledger first, accepts only eligible on-time success or duplicate, confirms Order and calls Voucher, Product, Course, and ledger participants atomically. | It must only be called after a durable eligible event has been committed and its dependencies exist. Optional configuration requires flags plus Course, Inventory, Voucher and ledger beans. The D readiness audit found no production Course/Inventory adapters and no registered D ledger adapter in the shown configuration. |
| `PaymentSessionApplicationService.createOrReuse(...)` | REQUIRED transaction; locks Order then its Attempts; persists an attempt before signing URL so signing failure rolls back the new attempt. It performs no remote call. | Reuse existing attempt/order lock convention; payment session creation is not an IPN endpoint and does not settle. |
| Spring security/configuration | `SecurityConfig` permits only listed Auth and Swagger paths, protects `/api/admin/**`, and requires authentication for all other paths. JWT filter runs before username/password auth. `VNPAY_ENABLED` and `VNPAY_IPN_ENABLED` default false. | D-3D conditionally registers the controller only when both flags are true, and permits only `GET /api/payments/vnpay/ipn` when both are true. Other methods and paths remain authenticated. Signature verification and durable reconciliation are still mandatory. |

### Payment status classification policy

`VnPayPaymentStatusClassifier` now uses explicit allowlists. Its output is an audit/payment-event classification only; it does not confirm an Order or authorize settlement. A suspicious or ambiguous result creates a `PaymentEvent` with `UNKNOWN` outcome and a `RECONCILIATION_REQUIRED` disposition. Existing expiration logic treats UNKNOWN attempt/event evidence as requiring review, and Settlement accepts only persisted eligible `SUCCEEDED` evidence.

| `vnp_ResponseCode` / `vnp_TransactionStatus` | Local classification | Evidence basis |
|---|---|---|
| `00 / 00` | `CONFIRMED_SUCCESS` → `SUCCEEDED` | Both fields individually document success. Requiring both is the existing strict local policy and is retained. |
| Response `09`, `10`, `11`, `12`, `13`, `24`, `51`, `65`, `75`, or `79` **and** status `02` | `DEFINITIVE_FAILURE` → `FAILED` | VNPay PAY error table documents these response codes as unsuccessful payment outcomes; the status table documents `02` as transaction error. Requiring this matching error status is a conservative local pairing rule; the docs do not publish a complete cross-product mapping for every pair. |
| Either field `07`, including `07 / 07` and `00 / 07` | `SUSPICIOUS_DEBIT_SUCCESS` → `UNKNOWN` | VNPay documents response `07` as debit success flagged suspicious, and transaction status `07` as suspicious fraud. Merchant must not auto-fulfill; local policy fails closed to review. |
| Any other pair, including response `00` with non-`00` status; documented failure response with status other than `02`; status `01`, `04`, `05`, `06`, `09`, `10`, or `20`; unrecognized numeric codes; malformed/missing data | `UNKNOWN_OR_CONTRADICTORY` → `UNKNOWN` | Status table describes pending, reversed, refund lifecycle, delivered, and settled states; these are not a definitive ordinary checkout failure mapping. Contradictions and unsupported combinations fail closed by local policy. |

The response code table and transaction status table are separate; VNPay's public docs do not establish a full pairwise mapping for every account/product/version. The failure pairing above is a deliberate narrow local policy, not a provider-published pair matrix. `99` and other generic/unlisted codes remain UNKNOWN. Recheck the merchant's current version/account documentation before production use.

The prior catch-all behavior that treated every well-formed non-`00/00` pair as `FAILED` was unsafe and has been replaced. The HTTP controller exists but remains disabled by default: both `VNPAY_ENABLED` and `VNPAY_IPN_ENABLED` default false.

## Proposed endpoint contract

| Property | Proposal |
|---|---|
| Path | `GET /api/payments/vnpay/ipn` (the public PAY guide specifies GET; confirm the merchant account's exact registered path and method before implementation). |
| Authentication | Public only at this exact route (`permitAll`); no customer JWT is expected. Public reachability is not trust: signature verification, TMN check, amount/reference routing, event idempotency, and settlement policy remain mandatory. |
| Parameter binding | Preserve each query key as a list of values and pass the full multi-value map into `VnPayCallbackVerifier`. Reject duplicated `vnp_` keys, null values, absent required fields, oversized input, and malformed encodings. Do not use a single-value map that silently chooses first/last value. Ignore unrelated non-`vnp_` parameters for signature construction, but apply an explicit bounded request policy. |
| Authentication evidence | Verify HMAC and merchant TMN before constructing/using trusted callback evidence. Never treat the browser Return URL as an IPN or settlement authority. |
| Protocol response | Implemented as fixed JSON `{"RspCode":"…","Message":"…"}`. `00` is returned only after reconciliation completes durably; it means the callback was recorded, not that the Order settled. Invalid signature uses `97`; TMN mismatch and malformed, duplicated, missing, or oversized input use `99`. Processing/persistence exceptions return HTTP 503 with `99`. |
| Duplicate | If the canonical signed event is already durably recorded and is identical, return VNPay's documented idempotent acknowledgment. If the event is conflicting, retain conflict/reconciliation evidence and return the specifically documented response that does not suppress required provider retry. |
| Invalid signature / malformed callback | Do not call reconciliation or settlement. Return a bounded failure response consistent with confirmed VNPay protocol. Avoid echoing request values or signatures. Record only safe aggregate diagnostics; do not persist unverified claims as verified reconciliation cases. |

The public [VNPay PAY integration guide](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html) is recorded as specifying GET, JSON fields `RspCode` and `Message`, and retry behavior for selected codes; the [VNPay error-code table](https://sandbox.vnpayment.vn/apis/docs/bang-ma-loi/) informs payment classification. These public sandbox references do not establish that the merchant's account/version uses identical callback and retry semantics. The implementation uses `00` to acknowledge a durably recorded reconciliation decision, including a durable review case, `97` for a bad signature, and `99` for other rejected input; account-specific interpretation and retry behavior remain unverified. Keep the feature flag off until VNPay confirms this mapping. `00` never means local Order settlement or fulfillment. No live gateway request was made.

### D-Payment-3D HTTP behavior

- Route: `GET /api/payments/vnpay/ipn`; Spring preserves query values as lists through the existing verifier. Parameters are decoded by the Servlet API; original raw query-string bytes are not retained. The verifier owns canonicalization and duplicate `vnp_` rejection.
- The controller bounds parameter count, key/value lengths, and aggregate decoded length before verification. It does not log parameter values, signatures, callback URLs, exception messages, or secrets.
- Signature/TMN verification occurs before reconciliation. Only `VnPayIpnReconciliationService` classifies and writes V5/V8 records. There is no browser-return handler.
- `00` follows successful return from the service's REQUIRED transaction. It acknowledges durable callback recording, not payment settlement. The controller does not call SettlementCoordinator or any voucher, Course, inventory, or refund operation.
- Reconciliation/persistence exceptions return HTTP 503 with JSON `RspCode=99`; a positive acknowledgement is never emitted for uncommitted audit data. Verification failures return fixed generic JSON without echoing rejected values.
- `app.payment.vnpay.ipn.enabled` maps from `VNPAY_IPN_ENABLED` and defaults false. The controller bean is also conditional on existing `VNPAY_ENABLED`. Security permits only the exact GET route when both flags are true; other methods and paths remain authenticated. Operators must explicitly enable both flags.

## Callback validation and routing sequence

1. Enforce route-specific HTTP method and request-size limits at the edge/controller. Preserve duplicate parameters as lists.
2. Call `VnPayCallbackVerifier.verify(...)`; reject malformed, duplicated, wrong-TMN, or bad-signature input before treating any callback value as authenticated evidence.
3. Capture one trusted receipt `Instant` from the injected clock. Keep it distinct from signed provider `vnp_PayDate`.
4. Resolve `(provider="vnpay", merchant_reference)` from persisted `payment_attempts`; never accept a client Order ID or user ID.
5. If no Attempt is found, create an idempotent V8 `UNKNOWN_MERCHANT_REFERENCE` case using verified bounded facts, a canonical event/evidence identity and digest. Do not create an Order/Attempt or treat the reference as a valid Order. The existing service currently does not do this.
6. For a known route, lock in the established order: Order, then PaymentAttempt. Verify the persisted Attempt-to-Order/user/provider/merchant route and snapshot currency/amount.
7. Parse the signed payment timestamp strictly. Preserve missing, malformed, and out-of-range states; do not convert them into a trustworthy provider payment instant. Existing code represents missing/invalid Event time internally as `Instant.EPOCH` with review classification; V8 can preserve timestamp state for a case, but the ordinary Event itself has only a non-null occurred time.
8. Convert signed amount minor units without truncation. A non-integral VND value is a mismatch. Compare against persisted attempt/order facts.
9. Derive the event identity deterministically from provider merchant reference and required provider transaction reference, then compare the complete event fingerprint. The current verifier requires `vnp_TransactionNo`, and the service forms `merchantReference:transactionReference`; a valid stable provider event ID beyond those signed fields has not been established. If the provider permits transaction reference reuse or sends corrections, preserve conflicting fingerprints as separate conflict evidence.
10. Apply the full confirmed response-code/status mapping. Unknown or suspicious statuses must be `UNKNOWN`/review, never definitive failure by default.
11. Persist the routed V5 `PaymentEvent` or `PaymentEventConflict`; persist applicable V8 cases for late, mismatched, ambiguous, unusable, or otherwise review-required evidence. V8 constraints require different field shapes for unlinked cases versus cases linked to a V5 event.
12. Only after a persisted event is known to be eligible may the processing path invoke `SettlementCoordinator`. Settlement independently reloads persisted evidence, checks the strict exclusive deadline and performs its own locks and participant writes.
13. Return the provider protocol acknowledgment only under the confirmed acknowledgment policy and only after required durable work or a durable retry obligation has committed.

## Reconciliation routing matrix

| Verified callback situation | Durable route available | Current behavior | Required HTTP/business treatment |
|---|---|---|---|
| Unknown merchant reference | V8 `UNKNOWN_MERCHANT_REFERENCE`, with no Order/Attempt/Event FKs | Classified in memory only; not persisted | Persist verified bounded case; never call Settlement. Acknowledge only per confirmed VNPay unknown-order response policy. |
| Known route, valid new event | V5 `payment_events` | Attempt decision and Event saved in reconciliation transaction | Classify; call Settlement only if durable event is eligible success. A normal failure event must not settle. |
| Identical duplicate event | Existing V5 Event | Returns `DUPLICATE_CALLBACK` with prior event ID | Idempotent replay; may retry Settlement only if prior persisted evidence remains eligible and no settlement exists. |
| Same event identity, changed fingerprint | V5 `payment_event_conflicts`; V8 `CONFLICTING_CALLBACK` can link event/conflict/route | Conflict and V8 case are written; Attempt is quarantined | Never overwrite prior successful result or settle the conflicting evidence. |
| Amount/currency mismatch | V5 Event contains mismatch reason; V8 linked case supports event route and amount for amount mismatch | Event is written with review disposition | Persist/use V8 case if required by operational reconciliation; do not settle. |
| Late provider success / expired or non-payable Order | V5 Event audit plus V8 `LATE_SUCCESS` or `ORDER_NOT_PAYABLE` | Event is classified; V8 case not wired | Persist durable case and retain evidence; no automatic settlement/refund/fulfillment. |
| Missing/malformed/out-of-range provider timestamp | V5 event has occurred_at but lacks timestamp-state column; V8 linked case has timestamp state/value checks | Event gets review classification and verification reference; V8 case not wired | Persist a V8 case preserving exact timestamp-state category, no invented occurred time for decision purposes; no settlement. |
| Unknown/ambiguous provider status | V5 supports `UNKNOWN`; V8 supports `UNKNOWN_PAYMENT_STATUS` | Attempt/Event may be set to UNKNOWN/review | Persist case and await provider/manual reconciliation; never treat as failure or success. |
| Settlement transient failure after Event commit | V8 has no normal eligible-event retry/work queue; V5 Event is durable | Settlement returns retryable only after coordinator-owned rollback; no outbox shown | Do not claim business settlement completed. Either return the confirmed retry-inducing protocol response, or first add a durable retry mechanism in a future approved schema/design phase. |
| Duplicate callback while settlement is in progress | Existing Order lock serializes settlement; settlement ledger is unique per Order | Request can block then observe ledger, subject to DB transaction timing | After bounded lock wait, retry/reload. Existing settlement yields `ALREADY_SETTLED`; never create a second ledger or effects. A transient lock error is not a business success. |

### V8 case representability limits

- **Can represent:** verified unknown references without Order/Attempt FKs; linked late, amount-mismatched, currency-mismatched, conflicting, invalid timestamp, unknown status, non-payable Order, settlement conflict, and unusable transaction-reference cases, subject to each route check.
- **Cannot directly represent:** a known merchant reference whose Attempt/Order route is absent or inconsistent while also satisfying the route FKs; current code throws on route inconsistency. That should be a bounded operational/security incident record or a V8 linked case only if a valid persisted route can be established. Do not attach it to a guessed Order.
- **Transient settlement failure:** no dedicated `SETTLEMENT_RETRY_PENDING` case type and no outbox/work row exists. `SETTLEMENT_CONFLICT` is not a generic retry queue and should not be overloaded for transient infrastructure errors.
- **Identical callback duplicate:** V5 Event + fingerprint and settlement uniqueness can make business replay idempotent; V8 may also deduplicate case evidence. A duplicate need not create a second case unless it adds new actionable evidence.
- **Case status mutability:** V8 allows OPEN/RESOLVED/DISMISSED with resolution fields but does not enforce resolution authorization or append-only resolution history by itself. Application policy/audit must do so.

### D-Payment-3C implemented V8 routing

`PaymentReconciliationCaseWriter` writes only verified callback evidence. The reconciler re-verifies its input before opening the REQUIRED transaction. It stores no signature, full URL, raw query string, or raw callback map; the payload digest is SHA-256 over the sorted signed fields. Unknown-reference cases also retain bounded V8 facts (merchant reference, minor amount, classified outcome, timestamp state/value when parseable, receipt time, and payload digest).

| Verified condition | V8 case type and route | Identity/replay behavior | Limit |
|---|---|---|---|
| Merchant reference has no Attempt route | `UNKNOWN_MERCHANT_REFERENCE`; event, Attempt, Order and conflict refs are NULL; signed merchant/amount/outcome/timestamp facts are present | Provider + deterministic event reference + type + `v1:` payload digest. Same evidence returns the canonical row; changed signed evidence creates a separate case and never overwrites the first. An unusable transaction number is represented by a bounded hash suffix in the event reference. | Unknown references never become PaymentEvents or valid Orders. |
| Known route, unusable transaction reference | `UNUSABLE_TRANSACTION_REFERENCE`; Attempt and Order required; Event/conflict refs NULL; bounded callback facts present. Attempt is marked UNKNOWN/reconciliation-required unless already SUCCEEDED. | Event reference uses merchant reference plus SHA-256 of the unusable transaction number; fingerprint uses the complete signed payload digest. | No V5 PaymentEvent is fabricated. |
| Suspicious `07`, unknown/contradictory status | `UNKNOWN_PAYMENT_STATUS`, linked to persisted Event/Attempt/Order | V5 event fingerprint is used; duplicate evidence resolves to existing case. | V8 linked route omits response/status fields, so the exact code pair cannot be recovered from the case row; only the linked event outcome/fingerprint and digest remain. |
| Late success; non-payable Order; amount/currency mismatch; invalid/missing/out-of-range timestamp | `LATE_SUCCESS`, `ORDER_NOT_PAYABLE`, `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `INVALID_PROVIDER_TIMESTAMP` respectively, linked to the persisted Event/Attempt/Order | Same V5 event identity and fingerprint; the exact minor amount is populated only for AMOUNT_MISMATCH as required by the CHECK. | Linked V8 route requires provider timestamp fields to be NULL. Timestamp state is preserved in V5 `verification_reference` (`paydate=valid|missing|invalid|out-of-range`); malformed/missing event time remains `Instant.EPOCH` in V5 as existing behavior. Currency mismatch is defensive and currently unreachable because the VNPay event currency is fixed to VND and stored Attempts are VND. |
| Conflicting callback with a persisted V5 conflict row | `CONFLICTING_CALLBACK`, linked to the first Event, conflict, Attempt and Order | Conflict FK uniqueness plus evidence unique key prevents duplicate case insertion. The conflict row and case are written in the same transaction. | Case references the first event and conflict route; it does not overwrite the first PaymentEvent. |
| Settlement conflict | Not currently emitted by this reconciler | The V8 type exists, but this service does not invoke settlement or receive a persisted settlement decision. | Do not write this case until a future, separately transacted settlement routing contract defines the exact persisted evidence and transaction boundary. |
| Inconsistent known route or transient persistence failure | No case is written | Reconciliation fails closed. | V8 cannot safely link a guessed route. Transient failures propagate; no retry/outbox work item is invented. |

V8's route CHECKs are exercised by H2 persistence tests: unlinked cases have no guessed FKs, unusable references have Attempt/Order but no Event, ordinary linked cases carry all required references, and a direct invalid route is rejected. The writer performs an identity lookup for the common replay path, but uniqueness is authoritative. A recognized unique-key race rolls back the service-owned transaction and retries once in a fresh transaction to load the winning case. It does not catch/continue in a rollback-only transaction. When called inside an ambient caller-owned transaction, it propagates the concurrency signal so that transaction owner must roll back; it cannot independently guarantee a committed re-read in that case.

These are H2 results only. H2 verifies the mapping and local transaction behavior, not MySQL/InnoDB lock scheduling or duplicate-key error translation. The guarded MySQL suite has not been run for this change.

## Settlement eligibility matrix

| Evidence/result | Call `SettlementCoordinator`? | Outcome |
|---|---:|---|
| Verified, persisted, new on-time success; correct route, amount/currency, valid timestamp; Order pending and before exclusive deadline | Yes, after reconciliation transaction commits | Settle atomically only if all required production adapters are present; otherwise remain disabled. |
| Identical duplicate of that same persisted eligible success | Yes, to obtain idempotent `ALREADY_SETTLED` or safely retry a previously rolled-back settlement | No duplicate effects; verify ledger/order state under locks. |
| Verified failure | No | Persist audit; no settlement. |
| Unknown/missing/ambiguous status or timestamp | No | Case/review; no automatic settlement. |
| Late provider success, receipt at/after deadline, or Order expired/cancelled | No | Durable reconciliation case; no automatic fulfillment or refund. |
| Amount/currency/routing mismatch | No | Durable review case; no settlement. |
| Conflicting callback or already-settled Order with a different event | No for conflicting event | Preserve evidence, do not overwrite immutable ledger or prior success. |
| Unknown merchant reference | No | V8 unlinked case only; not a valid Order. |
| Verified browser Return URL | Never | Display status only from authenticated/persisted server data; no settlement authority. |

Eligibility must preserve the existing policy: provider payment timestamp **and** local IPN receipt timestamp are strictly before the persisted Order deadline; equality is expired. Provider signature alone is not eligibility. Settlement must create one immutable settlement per Order and atomically confirm Order, consume Voucher, confirm Course claim and Product reservation. No C/E production confirmation adapters exist in the reviewed readiness baseline, so production settlement remains blocked.

## Transaction boundaries and acknowledgment semantics

The reconciler owns a REQUIRED `TransactionTemplate`; case persistence and corresponding attempt/event/conflict writes commit together. The settlement coordinator owns a separate transaction template. The intended boundary remains: **commit reconciliation audit first, then settle using persisted event identity in a second transaction**. A failure in settlement must not erase the verified callback audit. Settlement already reloads the durable Event and locks Order before Attempt; preserve this order and require every participant to use the same local datasource/transaction manager. No remote HTTP calls belong in either transaction.

This two-transaction design creates an acknowledgment decision:

- A protocol-level “received/recorded” response is safe only if VNPay's documented semantics distinguish receipt acknowledgment from merchant business settlement and a durable internal mechanism guarantees retry of eligible but unsettled events.
- A “business processing succeeded” response is safe only after settlement returns `SETTLED`/`ALREADY_SETTLED`, or after a durable retry obligation has committed.
- V5/V8 currently persist the event but do not persist a normal eligible event's settlement work state. Relying on VNPay retry after acknowledging receipt is unsafe unless the provider contract confirms retry behavior and no success acknowledgment was sent prematurely.
- Until this is resolved, do not implement the controller response policy or enable settlement. A later design may use a durable outbox/work table, or keep the endpoint retryable until settlement succeeds; choose only after confirming VNPay retry semantics and operational limits.

Do not wrap reconciliation and settlement in one ambient transaction accidentally: the reconciliation service's proxy transaction could otherwise encompass a nested coordinator call, making audit and settlement rollback semantics unclear. If orchestration is added, put the call boundary in a non-transactional application service, let reconciliation commit, then call the coordinator. Settlement's own REQUIRED boundary must complete before the response is built.

## Security and failure analysis

- **Public-route scope:** permit only the exact IPN path/method. Do not disable JWT or authorization globally. The JWT filter must not turn malformed or absent bearer tokens into a bypass of callback verification; test the filter-chain behavior explicitly.
- **Replay protection:** use the deterministic provider/event identity plus immutable fingerprints, V5 uniqueness, conflict rows and V8 cases. A valid signature can be replayed; signature verification alone is not replay protection.
- **Canonicalization:** the verifier excludes only `vnp_SecureHash`/`vnp_SecureHashType`, verifies all `vnp_` parameters as single-valued, and computes canonical HMAC. Controller binding must preserve raw decoded values consistently with VNPay's signing contract. Do not normalize Unicode, spaces, plus signs, case, or percent encoding before verifier input unless official protocol guidance requires it.
- **Time:** capture local receipt time once. Verify signed provider time and separately enforce strict Order deadline at reconciliation/settlement. Use UTC `Instant` mappings and retain the provider's timestamp-state classification.
- **Transactions:** lock Order then Attempt for reconciliation/settlement; save audit event/conflict and attempt decision together. Settlement lock order begins Order then Attempt and then its reservations/resources. Keep V8 case insertion consistent with this order. Avoid a case writer that locks a case first and then Order/Attempt, which could create a cycle.
- **Sensitive logs:** never log secret key, secure hash, full callback URL/query string, raw parameter map, full OrderInfo, or payment URL. Log request correlation ID, bounded classification, and internal IDs only after route resolution. Avoid storing the signature; V8's payload digest and bounded facts suffice. Treat raw IP and bank/card metadata as sensitive; store only if a reviewed operational need and retention policy exist.
- **Abuse controls:** configure request/query size limits, rate limits and bounded DB lock/query timeouts at ingress. Rate limiting must not silently discard legitimate retries; use provider/source-aware limits and monitor rejected volume. Restrict accepted parameter count and value lengths to documented bounds.
- **Errors:** map invalid callback, unknown merchant, duplicate, conflict, business rejection and transient DB failure separately. Never return an exception trace, entity details, or secret-bearing request data. A transient settlement failure must not be mislabeled as successful business completion.
- **Configuration:** VNPay defaults disabled. Enabling requires sandbox/production-specific endpoint, exact merchant credentials stored outside source control, HTTPS Return/IPN URLs, verified API version/command, and secret rotation procedures. Do not log bound properties or include credentials in tests/fixtures.

## Missing provider specifications to confirm

Before endpoint implementation, obtain the account-specific current VNPay specification and confirm:

1. Confirm the merchant's active callback HTTP method, query encoding, registered path and TLS requirements against the guide's GET/HTTPS contract.
2. Confirm exact response JSON field names, content type, and `RspCode`/`Message` handling for this account; the public guide shows JSON and lists acknowledgment/retry categories.
3. Confirm retry schedule/limits and behavior on non-2xx HTTP responses for this account; public guide currently states up to 10 retries at five-minute intervals for listed retry responses/timeouts.
4. Complete `vnp_ResponseCode` and `vnp_TransactionStatus` tables for the configured payment product/version, especially code `07`, reversal/chargeback/unknown states, and whether `00`/`00` is the sole final-success pair.
5. Whether `vnp_TransactionNo` is globally stable and unique for one payment, can be reused across attempts, and whether callbacks may legitimately revise outcome for the same reference.
6. Whether `vnp_PayDate` is mandatory for IPN, its timezone, precision, and correction behavior. Current code interprets it as `Asia/Ho_Chi_Minh`, seconds precision.
7. Exact signature canonicalization and URL/form decoding requirements for repeated keys, empty values, UTF-8, plus/space, and percent encoding, for the configured API version.
8. Required callback field set and documented maximum lengths. Current verifier requires `vnp_BankCode`, `vnp_OrderInfo`, and `vnp_TransactionNo`; verify these are required for this IPN product, not only browser return.

The public [VNPay PAY guide](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html) specifies GET for IPN, a JSON `RspCode`/`Message` acknowledgment, and documents retry behavior for selected merchant response codes/timeouts. The [VNPay error-code table](https://sandbox.vnpayment.vn/apis/docs/bang-ma-loi/) documents response `07` as debit success flagged suspicious and transaction status `07` as suspicious fraud. These published meanings informed the matrix above; confirm they apply to the actual merchant account and integration version.

## Proposed implementation and test phases

1. **D-Payment-3B: protocol contract lock-down.** D-Payment-3B1 implements the classifier and tests. Confirm the remaining provider points above; make no controller until the active merchant response/retry semantics are settled.
2. **D-Payment-3C: durable reconciliation routing.** Implemented and internally tested on isolated H2; see the routing matrix below. MySQL concurrency remains unverified by this phase.
3. **D-Payment-3D: gated HTTP boundary.** Implemented as described above: exact route, multi-value binding, input bounds, narrow `permitAll`, JSON protocol DTO, and tests for signature/malformed/duplicate/transient failures. Keep both feature flags off by default.
4. **D-Payment-3E: durable settlement retry decision.** Either establish provider retry behavior that safely supports no-ack on transient settlement failure, or propose a durable local retry/outbox record and its migration. Do not overload reconciliation cases as a work queue.
5. **D-Payment-3F: production settlement wiring.** Only after real C/E Course and Inventory transaction-bound adapters plus D ledger registration exist; test rollback and exactly-once effects on isolated H2 and guarded MySQL before any enablement.
6. **Pre-activation review.** Exercise provider sandbox only after explicit separate authorization, verify secret handling and monitoring, then obtain production activation approval. Browser Return remains display-only throughout.

## Production activation blockers

- The IPN HTTP boundary is implemented but gated off by default. Provider response-code interpretation and retry behavior still need confirmation for the actual merchant account/version.
- The V8 writer and routing are available to the gated HTTP boundary; there is still no durable settlement retry workflow.
- The allowlist classifier and V8 routing have isolated H2 tests, but are not production activated; the narrow failure pairing must be confirmed against the merchant account's active provider specification, and MySQL concurrency/error translation remains unverified.
- No durable retry queue exists for a verified eligible event whose settlement transaction fails after reconciliation commits; acknowledgment behavior cannot be selected safely yet.
- No production Course or Inventory settlement adapters exist; D's settlement ledger adapter also needs explicit bean registration.
- Provider response/status cross-product, acknowledgment/retry, timestamp and canonicalization details must be confirmed for the actual VNPay product/version.
- VNPay configuration is disabled by default; enabling requires valid secrets/URLs, monitoring, rate/request limits, and operational reconciliation ownership.

## Verification record

- D-Payment-3D focused H2/MockMvc checks: 33/33 passed across `VnPayIpnControllerTest` (6), `VnPayIpnReconciliationJpaH2IntegrationTest` (22), and `SecurityAuthorizationIntegrationTest` (5).
- Full default isolated H2 suite: 336/336 passed. The user-provided pre-phase guarded MySQL result was 35/35; MySQL was not rerun for D-Payment-3D.
- `git diff --check` passed. No VNPay calls or MySQL/persistent H2 connections were made.
