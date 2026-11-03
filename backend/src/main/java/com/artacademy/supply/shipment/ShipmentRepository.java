package com.artacademy.supply.shipment;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    @EntityGraph(attributePaths = "items")
    Optional<Shipment> findWithItemsById(Long id);

    @EntityGraph(attributePaths = "items")
    Optional<Shipment> findByOrderId(Integer orderId);

    List<Shipment> findByUserIdOrderByCreatedAtDesc(Integer userId);

    Page<Shipment> findByStatusIn(Collection<ShipmentStatus> statuses, Pageable pageable);

    long countByStatus(ShipmentStatus status);

    /** Id các chuyến đã giao thành công cho người này có chứa sản phẩm, mới nhất trước. */
    @Query("select s.id from Shipment s join s.items i "
            + "where s.userId = :userId and i.productId = :productId and s.status = :status "
            + "order by s.deliveredAt desc")
    List<Long> findShipmentIds(@Param("userId") Integer userId, @Param("productId") Long productId,
            @Param("status") ShipmentStatus status);

    @Query("select count(s) > 0 from Shipment s join s.items i "
            + "where s.userId = :userId and i.productId = :productId")
    boolean existsForUserAndProduct(@Param("userId") Integer userId, @Param("productId") Long productId);

    /** Đổi trạng thái chỉ khi đang ở trạng thái cũ, chống bấm hai lần hoặc hai người cùng thao tác. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Shipment s set s.status = :to, s.updatedAt = :now where s.id = :id and s.status = :from")
    int transition(@Param("id") Long id, @Param("from") ShipmentStatus from, @Param("to") ShipmentStatus to,
            @Param("now") LocalDateTime now);
}
