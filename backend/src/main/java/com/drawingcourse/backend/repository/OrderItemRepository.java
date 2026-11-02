package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.OrderItemJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface OrderItemRepository extends Repository<OrderItemJpaEntity, Integer> {
    Optional<OrderItemJpaEntity> findByIdAndOrder_IdAndOrder_UserId(Integer id, Integer orderId, Integer userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from OrderItemJpaEntity item where item.id=:id and item.order.id=:orderId and item.order.userId=:userId")
    Optional<OrderItemJpaEntity> findForUpdate(@Param("id") Integer id,
                                                @Param("orderId") Integer orderId,
                                                @Param("userId") Integer userId);
}
