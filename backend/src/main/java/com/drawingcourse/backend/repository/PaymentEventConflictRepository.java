package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.PaymentEventConflictJpaEntity;
import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface PaymentEventConflictRepository extends Repository<PaymentEventConflictJpaEntity,Integer> {
    Optional<PaymentEventConflictJpaEntity> findByFirstEventIdAndConflictingFingerprint(Integer firstEventId,String conflictingFingerprint);
    <S extends PaymentEventConflictJpaEntity> S saveAndFlush(S row);
}
