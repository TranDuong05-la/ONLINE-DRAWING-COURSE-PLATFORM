package com.artacademy.bootstrap;

import com.drawingcourse.backend.service.settlement.SettlementTimeoutProperties;
import com.drawingcourse.backend.service.settlement.SettlementTransactionBoundary;
import com.drawingcourse.backend.service.settlement.SettlementWorkClaimService;
import com.drawingcourse.backend.repository.PaymentSettlementWorkClaimRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.*;

class SettlementTransactionBoundaryTest {
    @Test
    void usesExplicitTimeoutAndStartsAnIndependentRequiredTransaction() {
        CapturingTransactionManager manager = new CapturingTransactionManager();
        SettlementTransactionBoundary boundary = new SettlementTransactionBoundary(manager,
                new SettlementTimeoutProperties());

        assertEquals("completed", boundary.execute(() -> "completed"));
        assertNotNull(manager.definition);
        assertEquals(60, manager.definition.getTimeout());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, manager.definition.getPropagationBehavior());
    }

    @Test
    void refusesToJoinAmbientTransaction() {
        CapturingTransactionManager manager = new CapturingTransactionManager();
        SettlementTransactionBoundary boundary = new SettlementTransactionBoundary(manager,
                new SettlementTimeoutProperties());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> boundary.execute(() -> "unsafe"));
            assertNull(manager.definition, "No joined or nested transaction should start");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void claimTransactionRetainsItsFiveSecondTimeout() {
        CapturingTransactionManager manager = new CapturingTransactionManager();
        PaymentSettlementWorkClaimRepository repository = mock(PaymentSettlementWorkClaimRepository.class);
        when(repository.databaseUtcNow()).thenReturn(Instant.parse("2026-10-29T00:00:00Z"));
        when(repository.lockReady(any())).thenReturn(Optional.empty());
        when(repository.lockRetryWait(any())).thenReturn(Optional.empty());
        when(repository.lockExpiredLease(any())).thenReturn(Optional.empty());
        when(repository.lockExhaustedLease(any())).thenReturn(Optional.empty());
        SettlementWorkClaimService claims = new SettlementWorkClaimService(repository, manager,
                new SettlementTimeoutProperties());

        assertTrue(claims.claimNext("test-worker").isEmpty());
        assertNotNull(manager.definition);
        assertEquals(5, manager.definition.getTimeout());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, manager.definition.getPropagationBehavior());
    }

    private static final class CapturingTransactionManager extends AbstractPlatformTransactionManager {
        private TransactionDefinition definition;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            this.definition = definition;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
