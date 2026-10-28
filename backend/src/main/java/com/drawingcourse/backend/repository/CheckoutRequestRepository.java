package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.CheckoutRequestJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CheckoutRequestRepository extends Repository<CheckoutRequestJpaEntity, Integer> {
    Optional<CheckoutRequestJpaEntity> findByUser_IdAndIdempotencyKey(Integer userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CheckoutRequestJpaEntity r where r.id = :id and r.user.id = :userId")
    Optional<CheckoutRequestJpaEntity> findByIdAndUserIdForUpdate(@Param("id") Integer id,
                                                                   @Param("userId") Integer userId);

    <S extends CheckoutRequestJpaEntity> S saveAndFlush(S request);
}
