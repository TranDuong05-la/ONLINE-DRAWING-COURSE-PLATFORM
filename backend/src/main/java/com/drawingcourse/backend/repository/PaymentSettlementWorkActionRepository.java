package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.PaymentSettlementWorkActionJpaEntity;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** Append-only operator-action contract; no update or delete methods are exposed. */
public interface PaymentSettlementWorkActionRepository
        extends Repository<PaymentSettlementWorkActionJpaEntity, Integer> {
    Optional<PaymentSettlementWorkActionJpaEntity> findById(Integer id);
    Optional<PaymentSettlementWorkActionJpaEntity> findByWorkItemIdAndActionKey(Integer workItemId, String actionKey);
    List<PaymentSettlementWorkActionJpaEntity> findByWorkItemIdOrderByIdAsc(Integer workItemId);

    <S extends PaymentSettlementWorkActionJpaEntity> S saveAndFlush(S action);
}
