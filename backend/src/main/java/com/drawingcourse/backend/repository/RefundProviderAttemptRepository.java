package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.RefundProviderAttemptJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface RefundProviderAttemptRepository extends Repository<RefundProviderAttemptJpaEntity, Long> {
    Optional<RefundProviderAttemptJpaEntity> findById(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from RefundProviderAttemptJpaEntity a where a.id=:id and a.refundRequestId=:requestId")
    Optional<RefundProviderAttemptJpaEntity> findForUpdate(@Param("id") Long id, @Param("requestId") Long requestId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from RefundProviderAttemptJpaEntity a where a.refundRequestId=:requestId and a.executionSlot=1")
    Optional<RefundProviderAttemptJpaEntity> findActiveSlotForUpdate(@Param("requestId") Long requestId);
    long countByRefundRequestId(Long requestId);
    <S extends RefundProviderAttemptJpaEntity> S saveAndFlush(S row);
}
