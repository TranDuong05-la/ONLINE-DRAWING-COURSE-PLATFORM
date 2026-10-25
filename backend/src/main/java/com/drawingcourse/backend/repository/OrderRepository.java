package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.OrderJpaEntity;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

/** Deliberately excludes delete and unscoped read methods. */
public interface OrderRepository extends Repository<OrderJpaEntity, Integer> {
    @Query("select o.userId from OrderJpaEntity o where o.id=:id")
    Optional<Integer> findUserIdById(@Param("id") Integer id);
    Optional<OrderJpaEntity> findByIdAndUserId(Integer id, Integer userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderJpaEntity o where o.id=:id and o.userId=:userId")
    Optional<OrderJpaEntity> findForUpdate(@Param("id") Integer id,@Param("userId") Integer userId);
    <S extends OrderJpaEntity> S saveAndFlush(S order);
}
