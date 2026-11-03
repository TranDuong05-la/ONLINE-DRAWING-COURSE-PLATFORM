package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.RefundEventJpaEntity;
import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface RefundEventRepository extends Repository<RefundEventJpaEntity, Long> {
    Optional<RefundEventJpaEntity> findById(Long id);
    Optional<RefundEventJpaEntity> findByRefundRequestIdAndOperationTypeAndEventKey(Long requestId, String operationType, String eventKey);
    java.util.List<RefundEventJpaEntity> findAllByRefundRequestIdOrderById(Long requestId);
    <S extends RefundEventJpaEntity> S saveAndFlush(S row);
}
