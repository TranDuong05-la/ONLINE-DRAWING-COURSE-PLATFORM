package com.drawingcourse.backend.service.settlement;

import com.drawingcourse.backend.entity.PaymentSettlementJpaEntity;
import com.drawingcourse.backend.repository.PaymentSettlementRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Real immutable JPA ledger adapter. It deliberately provides no update or delete operation. */
@Repository
public class JpaSettlementLedgerAdapter implements SettlementLedgerPort {
    private final PaymentSettlementRepository settlements;

    public JpaSettlementLedgerAdapter(PaymentSettlementRepository settlements) {
        this.settlements = Objects.requireNonNull(settlements);
    }

    @Override
    public Optional<SettlementReference> findForUpdateByOrderId(Integer orderId) {
        requireTransaction();
        return settlements.findForUpdateByOrderId(orderId).map(JpaSettlementLedgerAdapter::reference);
    }

    @Override
    public SettlementReference insert(Integer orderId, Integer attemptId, Integer eventId,
                                      Instant providerPaidAt, Instant settledAt) {
        requireTransaction();
        var saved = settlements.saveAndFlush(PaymentSettlementJpaEntity.record(
                orderId, attemptId, eventId, providerPaidAt, settledAt));
        return reference(saved);
    }

    private static SettlementReference reference(PaymentSettlementJpaEntity row) {
        return new SettlementReference(row.getId(), row.getOrderId(), row.getPaymentAttemptId(), row.getPaymentEventId());
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Settlement ledger access requires the caller transaction");
    }
}
