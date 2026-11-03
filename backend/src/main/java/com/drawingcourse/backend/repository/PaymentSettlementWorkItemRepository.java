package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.PaymentSettlementWorkItemJpaEntity;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/** Read/insert contract only. Claim/lease locking APIs belong to the future worker phase. */
public interface PaymentSettlementWorkItemRepository
        extends Repository<PaymentSettlementWorkItemJpaEntity, Integer> {
    Optional<PaymentSettlementWorkItemJpaEntity> findById(Integer id);
    Optional<PaymentSettlementWorkItemJpaEntity> findByPaymentEventId(Integer paymentEventId);
    <S extends PaymentSettlementWorkItemJpaEntity> S saveAndFlush(S workItem);
}
