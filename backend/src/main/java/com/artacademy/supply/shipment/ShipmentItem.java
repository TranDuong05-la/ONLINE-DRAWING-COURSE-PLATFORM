package com.artacademy.supply.shipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Món trong chuyến giao. Tên/SKU lưu bản chụp để sửa sản phẩm không làm đổi đơn cũ. */
@Entity
@Table(name = "shipment_items")
@Getter
@Setter
public class ShipmentItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(nullable = false, length = 50)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean gift;
}
