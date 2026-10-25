package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.OrderShippingAddressJpaEntity;
import org.springframework.data.repository.Repository;
import java.util.Optional;

/** Owner-scoped access to the immutable address snapshot attached to an Order. */
public interface OrderShippingAddressRepository extends Repository<OrderShippingAddressJpaEntity,Integer> {
    Optional<OrderShippingAddressJpaEntity> findByOrder_IdAndOrder_UserId(Integer orderId,Integer userId);
    <S extends OrderShippingAddressJpaEntity> S saveAndFlush(S address);
}
