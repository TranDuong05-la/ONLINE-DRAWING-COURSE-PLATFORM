package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.PaymentEventConflictJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;

public interface PaymentEventConflictRepository extends Repository<PaymentEventConflictJpaEntity,Integer> {
    Optional<PaymentEventConflictJpaEntity> findByFirstEventIdAndConflictingFingerprint(Integer firstEventId,String conflictingFingerprint);
    List<PaymentEventConflictJpaEntity> findAllByOrderIdAndUserId(Integer orderId, Integer userId);
    /** Current locking read pairs with Order->Attempt locking and sees committed conflicts after any wait. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentEventConflictJpaEntity c where c.orderId=:orderId and c.userId=:userId order by c.id")
    List<PaymentEventConflictJpaEntity> findAllForOrderUpdate(@Param("orderId") Integer orderId, @Param("userId") Integer userId);
    <S extends PaymentEventConflictJpaEntity> S saveAndFlush(S row);
}
