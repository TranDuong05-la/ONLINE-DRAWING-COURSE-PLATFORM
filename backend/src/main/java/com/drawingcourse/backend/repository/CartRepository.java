package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.CartJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface CartRepository extends JpaRepository<CartJpaEntity, Integer> {
    Optional<CartJpaEntity> findByUserId(Integer userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CartJpaEntity c where c.userId = :userId")
    Optional<CartJpaEntity> findByUserIdForUpdate(@Param("userId") Integer userId);
}
