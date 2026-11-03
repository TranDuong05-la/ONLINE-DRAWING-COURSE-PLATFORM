package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.PaymentEventJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;

/** Immutable audits: no event handler, save/merge or delete operation is activated. */
public interface PaymentEventRepository extends Repository<PaymentEventJpaEntity,Integer> {
    Optional<PaymentEventJpaEntity> findById(Integer id);
    Optional<PaymentEventJpaEntity> findByProviderAndEventReferenceAndUserId(String provider,String eventReference,Integer userId);
    Optional<PaymentEventJpaEntity> findByProviderAndEventReference(String provider,String eventReference);
    List<PaymentEventJpaEntity> findAllByOrderIdAndUserId(Integer orderId,Integer userId);
    /** Current locking read: expiry may have waited behind an IPN writer while an RR snapshot was established. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PaymentEventJpaEntity e where e.orderId=:orderId and e.userId=:userId order by e.id")
    List<PaymentEventJpaEntity> findAllForOrderUpdate(@Param("orderId") Integer orderId, @Param("userId") Integer userId);
    <S extends PaymentEventJpaEntity> S saveAndFlush(S row);
}
