package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.OrderItemJpaEntity;
import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface OrderItemRepository extends Repository<OrderItemJpaEntity, Integer> {
    Optional<OrderItemJpaEntity> findByIdAndOrder_IdAndOrder_UserId(Integer id, Integer orderId, Integer userId);
}
