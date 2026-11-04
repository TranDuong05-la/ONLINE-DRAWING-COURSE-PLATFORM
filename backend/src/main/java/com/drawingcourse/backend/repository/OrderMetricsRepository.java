package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.OrderJpaEntity;
import com.drawingcourse.backend.entity.OrderStatus;
import java.util.Collection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Aggregate-only reads of Orders for the admin dashboard; never loads Order entities and has no write methods. */
public interface OrderMetricsRepository extends Repository<OrderJpaEntity, Integer> {
    @Query("select count(o) from OrderJpaEntity o")
    long countAllOrders();

    @Query("select coalesce(sum(o.totalVnd), 0) from OrderJpaEntity o where o.status in :statuses")
    long sumTotalVndByStatusIn(@Param("statuses") Collection<OrderStatus> statuses);
}
