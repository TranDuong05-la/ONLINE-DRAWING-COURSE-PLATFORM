package com.artacademy.supply.inventory;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    @Query("select r from StockReservation r join fetch r.product where r.orderId = :orderId "
            + "order by r.product.id, r.gift")
    List<StockReservation> findByOrderIdWithProduct(@Param("orderId") Integer orderId);

    /** Đổi trạng thái chỉ khi đang ở trạng thái cũ. Trả 1 nếu thành công, 0 nếu đã bị đổi trước đó. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update StockReservation r set r.status = :to, r.updatedAt = :now where r.id = :id and r.status = :from")
    int transition(@Param("id") Long id, @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to, @Param("now") LocalDateTime now);
}
