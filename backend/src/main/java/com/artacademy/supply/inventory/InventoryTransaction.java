package com.artacademy.supply.inventory;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.artacademy.product.Product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Lịch sử mọi thay đổi tồn kho. Chỉ thêm, không sửa/xóa. */
@Entity
@Table(name = "inventory_transactions", indexes = {
        @Index(name = "idx_inv_tx_product", columnList = "product_id, created_at"),
        @Index(name = "idx_inv_tx_order", columnList = "order_id")
})
@Getter
@Setter
public class InventoryTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InventoryTransactionType type;

    /** Luôn dương; chiều tăng/giảm suy ra từ type. */
    @Column(nullable = false)
    private int quantity;

    @Column(name = "order_id")
    private Integer orderId;

    @Column(name = "shipment_id")
    private Long shipmentId;

    /** Hàng quà kèm khóa học. */
    @Column(nullable = false)
    private boolean gift;

    @Column(name = "on_hand_after", nullable = false)
    private int onHandAfter;

    @Column(name = "reserved_after", nullable = false)
    private int reservedAfter;

    @Column(length = 500)
    private String note;

    @Column(name = "actor_user_id")
    private Integer actorUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
