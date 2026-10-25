package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "order_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_items_reference", columnNames = {"order_id", "item_type", "item_id"}),
        @UniqueConstraint(name = "uk_order_items_position", columnNames = {"order_id", "line_position"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_order_items_order"))
    private OrderJpaEntity order;
    @Column(name = "line_position", nullable = false, updatable = false)
    private int linePosition;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "item_type", nullable = false, length = 16, updatable = false)
    private OrderItemType itemType;
    @Column(name = "item_id", nullable = false, updatable = false)
    private Integer itemId;
    @Column(name = "name_snapshot", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String nameSnapshot;
    @Column(nullable = false, updatable = false)
    private int quantity;
    @Column(name = "unit_price_vnd", nullable = false, updatable = false)
    private long unitPriceVnd;
    @Column(name = "voucher_eligible", nullable = false, updatable = false)
    private boolean voucherEligible;
    @Column(name = "gross_amount_vnd", nullable = false, updatable = false)
    private long grossAmountVnd;
    @Column(name = "allocated_discount_vnd", nullable = false, updatable = false)
    private long allocatedDiscountVnd;
    @Column(name = "net_amount_vnd", nullable = false, updatable = false)
    private long netAmountVnd;

    OrderItemJpaEntity(OrderJpaEntity order, int position, OrderItem snapshot) {
        this.order = order;
        linePosition = position;
        var line = snapshot.catalogSnapshot();
        itemType = line.itemType(); itemId = line.itemId(); nameSnapshot = line.name();
        quantity = line.quantity(); unitPriceVnd = line.unitPriceVnd(); voucherEligible = line.voucherEligible();
        grossAmountVnd = snapshot.grossAmountVnd();
        allocatedDiscountVnd = snapshot.allocatedDiscountVnd();
        netAmountVnd = snapshot.netAmountVnd();
    }
}
