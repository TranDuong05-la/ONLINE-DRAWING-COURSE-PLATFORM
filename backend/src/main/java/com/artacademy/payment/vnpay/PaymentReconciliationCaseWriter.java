package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.PaymentReconciliationCaseJpaEntity;
import com.drawingcourse.backend.repository.PaymentReconciliationCaseRepository;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Objects;

/** Persists bounded verified evidence using the V8 unique identity; it never resolves cases. */
final class PaymentReconciliationCaseWriter {
    private final PaymentReconciliationCaseRepository cases;

    PaymentReconciliationCaseWriter(PaymentReconciliationCaseRepository cases) {
        this.cases = Objects.requireNonNull(cases);
    }

    PaymentReconciliationCaseJpaEntity write(PaymentReconciliationCaseJpaEntity candidate) {
        var existing = cases.findByProviderAndEventReferenceAndCaseTypeAndEvidenceFingerprint(
                candidate.getProvider(), candidate.getEventReference(), candidate.getCaseType(),
                candidate.getEvidenceFingerprint());
        if (existing.isPresent()) return existing.get();

        if (candidate.getPaymentEventConflictId() != null) {
            var byConflict = cases.findByPaymentEventConflictId(candidate.getPaymentEventConflictId());
            if (byConflict.isPresent()) return byConflict.get();
        }

        try {
            return cases.saveAndFlush(candidate);
        } catch (DataIntegrityViolationException violation) {
            if (isIntendedIdentityViolation(violation)) throw new ConcurrentCaseInsertException(violation);
            throw violation;
        }
    }

    private static boolean isIntendedIdentityViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            boolean intendedKey = message != null
                    && (message.toLowerCase(java.util.Locale.ROOT).contains("uk_payment_reconciliation_evidence")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("uk_payment_reconciliation_conflict"));
            if (current instanceof SQLException sql) {
                boolean duplicateKey = "23505".equals(sql.getSQLState())
                        || ("23000".equals(sql.getSQLState()) && sql.getErrorCode() == 1062);
                if (duplicateKey && intendedKey) return true;
            }
        }
        return false;
    }

    static final class ConcurrentCaseInsertException extends RuntimeException {
        ConcurrentCaseInsertException(Throwable cause) {
            super("Concurrent reconciliation case insert; transaction must roll back and be retried", cause);
        }
    }
}
