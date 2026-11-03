package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.RefundReviewCaseJpaEntity;
import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface RefundReviewCaseRepository extends Repository<RefundReviewCaseJpaEntity, Long> {
    Optional<RefundReviewCaseJpaEntity> findById(Long id);
    Optional<RefundReviewCaseJpaEntity> findByRefundRequestIdAndCaseKey(Long requestId, String caseKey);
    java.util.List<RefundReviewCaseJpaEntity> findAllByRefundRequestIdOrderById(Long requestId);
    <S extends RefundReviewCaseJpaEntity> S saveAndFlush(S row);
}
