package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.PaymentReconciliationCaseJpaEntity;
import org.springframework.data.repository.Repository;
import java.util.Optional;

/** Reconciliation case persistence with identity-based replay lookup and no deletion API. */
public interface PaymentReconciliationCaseRepository extends Repository<PaymentReconciliationCaseJpaEntity, Integer> {
    Optional<PaymentReconciliationCaseJpaEntity> findById(Integer id);
    Optional<PaymentReconciliationCaseJpaEntity> findByProviderAndEventReferenceAndCaseTypeAndEvidenceFingerprint(
            String provider, String eventReference, String caseType, String evidenceFingerprint);
    Optional<PaymentReconciliationCaseJpaEntity> findByPaymentEventConflictId(Integer paymentEventConflictId);
    <S extends PaymentReconciliationCaseJpaEntity> S saveAndFlush(S reconciliationCase);
}
