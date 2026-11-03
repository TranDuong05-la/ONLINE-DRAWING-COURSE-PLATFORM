package com.drawingcourse.backend.service.settlement;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** Settlement-scoped transaction boundary. REQUIRED is independent because ambient transactions are rejected. */
public final class SettlementTransactionBoundary {
    private final TransactionTemplate transaction;

    public SettlementTransactionBoundary(PlatformTransactionManager manager, SettlementTimeoutProperties properties) {
        Objects.requireNonNull(properties, "properties");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transaction.setTimeout(Math.toIntExact(properties.getTransactionTimeout().toSeconds()));
    }

    public <T> T execute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Settlement transaction cannot join an ambient transaction");
        return transaction.execute(status -> operation.get());
    }
}
