package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.PaymentReconciliationCaseJpaEntity;
import com.drawingcourse.backend.repository.PaymentReconciliationCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentReconciliationCaseWriterTest {
    private static final String PAYLOAD_DIGEST = "a".repeat(64);

    @Test
    void onlyV8CaseIdentityUniqueConstraintsAreClassifiedAsConcurrentReplay() {
        var repository = emptyRepository();
        var duplicate = new DataIntegrityViolationException("duplicate", new SQLException(
                "Duplicate entry for key 'payment_reconciliation_cases.uk_payment_reconciliation_evidence'", "23000", 1062));
        when(repository.saveAndFlush(any())).thenThrow(duplicate);
        assertThrows(PaymentReconciliationCaseWriter.ConcurrentCaseInsertException.class,
                () -> new PaymentReconciliationCaseWriter(repository).write(candidate()));
    }

    @Test
    void unrelatedUniqueConstraintViolationIsNotClassifiedAsReplay() {
        var repository = emptyRepository();
        var unrelated = new DataIntegrityViolationException("duplicate", new SQLException(
                "Duplicate entry for key 'uk_another_table_unique'", "23505", 23505));
        when(repository.saveAndFlush(any())).thenThrow(unrelated);
        assertSame(unrelated, assertThrows(DataIntegrityViolationException.class,
                () -> new PaymentReconciliationCaseWriter(repository).write(candidate())));
    }

    @Test
    void foreignKeyViolationIsNotClassifiedAsReplay() {
        var repository = emptyRepository();
        var foreignKey = new DataIntegrityViolationException("foreign key", new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails", "23000", 1452));
        when(repository.saveAndFlush(any())).thenThrow(foreignKey);
        assertSame(foreignKey, assertThrows(DataIntegrityViolationException.class,
                () -> new PaymentReconciliationCaseWriter(repository).write(candidate())));
    }

    private PaymentReconciliationCaseRepository emptyRepository() {
        var repository = mock(PaymentReconciliationCaseRepository.class);
        when(repository.findByProviderAndEventReferenceAndCaseTypeAndEvidenceFingerprint(
                "vnpay", "test-ref:123", "UNKNOWN_MERCHANT_REFERENCE", "v1:" + PAYLOAD_DIGEST))
                .thenReturn(Optional.empty());
        return repository;
    }

    private PaymentReconciliationCaseJpaEntity candidate() {
        return PaymentReconciliationCaseJpaEntity.unknownMerchant("vnpay", "test-ref:123",
                "UNKNOWN_MERCHANT_REFERENCE", "v1:" + PAYLOAD_DIGEST, "test-ref", 12300,
                "UNKNOWN", "MISSING", null, Instant.parse("2026-10-29T00:00:00Z"), PAYLOAD_DIGEST);
    }
}
