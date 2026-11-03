package com.artacademy.supply.inventory;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {
    Page<InventoryTransaction> findByProductIdOrderByCreatedAtDescIdDesc(Long productId, Pageable pageable);

    List<InventoryTransaction> findByOrderIdOrderByIdAsc(Integer orderId);

    long countByOrderIdAndType(Integer orderId, InventoryTransactionType type);
}
