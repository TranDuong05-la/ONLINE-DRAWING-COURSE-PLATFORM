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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Hàng đang giữ cho một đơn của D. Mỗi (đơn, sản phẩm, là quà?) một dòng.
 * Trạng thái chỉ đổi bằng câu UPDATE có điều kiện "status = trạng thái cũ",
 * nên trả giữ hoặc xuất kho chỉ xảy ra đúng một lần dù bị gọi lặp.
 */
@Entity
@Table(name = "stock_reservations", uniqueConstraints = @UniqueConstraint(
        name = "uk_reservation_order_product_gift", columnNames = {"order_id", "product_id", "gift"}))
@Getter
@Setter
public class StockReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Integer orderId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean gift;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.RESERVED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
