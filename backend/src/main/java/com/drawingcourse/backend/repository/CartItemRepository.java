package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.CartItemJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItemJpaEntity, Integer> {
    Optional<CartItemJpaEntity> findByIdAndCart_IdAndCart_UserId(
            Integer id, Integer cartId, Integer userId);
}
