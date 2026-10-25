package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.PaymentEventJpaEntity;
import org.springframework.data.repository.Repository;
import java.util.Optional;
import java.util.List;

/** Immutable audits: no event handler, save/merge or delete operation is activated. */
public interface PaymentEventRepository extends Repository<PaymentEventJpaEntity,Integer> {
    Optional<PaymentEventJpaEntity> findById(Integer id);
    Optional<PaymentEventJpaEntity> findByProviderAndEventReferenceAndUserId(String provider,String eventReference,Integer userId);
    Optional<PaymentEventJpaEntity> findByProviderAndEventReference(String provider,String eventReference);
    List<PaymentEventJpaEntity> findAllByOrderIdAndUserId(Integer orderId,Integer userId);
    <S extends PaymentEventJpaEntity> S saveAndFlush(S row);
}
