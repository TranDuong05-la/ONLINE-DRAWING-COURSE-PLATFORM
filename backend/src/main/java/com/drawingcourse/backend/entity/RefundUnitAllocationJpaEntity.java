package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static com.drawingcourse.backend.entity.RefundPersistenceTypes.UnitAllocationStatus;

@Entity
@Table(name = "refund_unit_allocations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_refund_unit_request_position", columnNames = {"refund_request_id", "unit_position"}),
        @UniqueConstraint(name = "uk_refund_unit_active_position", columnNames = {"order_item_id", "unit_position", "active_marker"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundUnitAllocationJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "refund_request_id", nullable = false, updatable = false) private Long refundRequestId;
    @Column(name = "order_item_id", nullable = false, updatable = false) private Integer orderItemId;
    @Column(name = "unit_position", nullable = false, updatable = false) private int unitPosition;
    @Column(name = "allocated_amount_vnd", nullable = false, updatable = false) private long allocatedAmountVnd;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "allocation_status", nullable = false, length = 16) private UnitAllocationStatus allocationStatus;
    @Column(name = "active_marker") private Byte activeMarker;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "released_at") private Instant releasedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "refund_request_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "order_item_id", referencedColumnName = "order_item_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_refund_unit_request_item"))
    private RefundRequestJpaEntity requestReference;

    public static RefundUnitAllocationJpaEntity reserve(Long requestId, Integer orderItemId, int position,
                                                        long amountVnd, Instant at) {
        if (requestId == null || requestId <= 0 || orderItemId == null || orderItemId <= 0 || position < 0 || amountVnd < 0)
            throw new IllegalArgumentException("Invalid unit allocation");
        var row = new RefundUnitAllocationJpaEntity();
        row.refundRequestId = requestId; row.orderItemId = orderItemId; row.unitPosition = position;
        row.allocatedAmountVnd = amountVnd; row.allocationStatus = UnitAllocationStatus.RESERVED;
        row.activeMarker = 1; row.createdAt = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MICROS);
        return row;
    }

    public void release(Instant at) {
        if (allocationStatus != UnitAllocationStatus.RESERVED) throw new IllegalStateException("Only reserved units can be released");
        allocationStatus = UnitAllocationStatus.RELEASED; activeMarker = null;
        releasedAt = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MICROS);
    }

    public void markRefunded() {
        if (allocationStatus != UnitAllocationStatus.RESERVED) throw new IllegalStateException("Only reserved units can be refunded");
        allocationStatus = UnitAllocationStatus.REFUNDED;
    }
}
