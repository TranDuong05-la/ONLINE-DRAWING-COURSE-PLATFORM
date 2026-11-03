package com.drawingcourse.backend.service.settlement;

import java.time.Instant;
import java.util.Optional;

/** Local immutable settlement ledger. Implementations must read/insert in the caller's transaction. */
public interface SettlementLedgerPort {
    Optional<SettlementReference> findForUpdateByOrderId(Integer orderId);
    SettlementReference insert(Integer orderId, Integer paymentAttemptId, Integer paymentEventId,
                               Instant providerPaidAt, Instant settledAt);

    record SettlementReference(Integer id, Integer orderId, Integer paymentAttemptId, Integer paymentEventId) { }
}
