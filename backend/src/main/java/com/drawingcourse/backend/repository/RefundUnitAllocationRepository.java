package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.RefundUnitAllocationJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface RefundUnitAllocationRepository extends Repository<RefundUnitAllocationJpaEntity, Long> {
    Optional<RefundUnitAllocationJpaEntity> findById(Long id);
    List<RefundUnitAllocationJpaEntity> findAllByRefundRequestIdOrderByUnitPosition(Long requestId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from RefundUnitAllocationJpaEntity u where u.refundRequestId=:requestId order by u.unitPosition,u.id")
    List<RefundUnitAllocationJpaEntity> findAllForRequestUpdate(@Param("requestId") Long requestId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from RefundUnitAllocationJpaEntity u where u.orderItemId=:itemId and u.activeMarker=1 order by u.unitPosition,u.id")
    List<RefundUnitAllocationJpaEntity> findActiveForItemUpdate(@Param("itemId") Integer itemId);
    <S extends RefundUnitAllocationJpaEntity> S saveAndFlush(S row);
}
