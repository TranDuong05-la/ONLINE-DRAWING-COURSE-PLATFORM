package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.OrderJpaEntity;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;

/** Deliberately excludes delete and unscoped read methods. */
public interface OrderRepository extends Repository<OrderJpaEntity, Integer> {
    @Query("select o.userId from OrderJpaEntity o where o.id=:id")
    Optional<Integer> findUserIdById(@Param("id") Integer id);

    Optional<OrderJpaEntity> findByIdAndUserId(Integer id, Integer userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderJpaEntity o where o.id=:id and o.userId=:userId")
    Optional<OrderJpaEntity> findForUpdate(@Param("id") Integer id, @Param("userId") Integer userId);

    <S extends OrderJpaEntity> S saveAndFlush(S order);

    @Query(value = "select o.id as orderId, o.status as status, o.paymentStatus as paymentStatus, o.createdAt as createdAt, o.updatedAt as updatedAt, o.paymentExpiresAt as paymentExpiresAt, o.totalVnd as totalVnd, o.currency as currency, (select count(i) from OrderItemJpaEntity i where i.order.id = o.id) as itemCount from OrderJpaEntity o where o.userId = :userId order by o.createdAt desc, o.id desc",
           countQuery = "select count(o) from OrderJpaEntity o where o.userId = :userId")
    Page<OrderHistoryProjection> findHistoryByUserId(@Param("userId") Integer userId, Pageable pageable);

    /** Owner-scoped existence check only; never loads Order or OrderItem entities. */
    @Query("select case when count(item) > 0 then true else false end " +
           "from OrderItemJpaEntity item join item.order candidate " +
           "where candidate.userId = :userId and item.itemId = :courseId " +
           "and item.itemType = :itemType and candidate.status = :orderStatus " +
           "and candidate.paymentExpiresAt > :observedAt")
    boolean existsActivePendingCoursePurchase(
            @Param("userId") Integer userId,
            @Param("courseId") Integer courseId,
            @Param("itemType") OrderItemType itemType,
            @Param("orderStatus") OrderStatus orderStatus,
            @Param("observedAt") Instant observedAt);
}
