package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** No catalog calls, voucher calculation, discount allocation, or row replacement on reads. */
public final class OrderPersistenceMapper {
    public OrderJpaEntity toNewEntity(Order domain) {
        return OrderJpaEntity.fromSnapshot(Objects.requireNonNull(domain));
    }

    public Order toDomain(OrderJpaEntity row) {
        var lines = orderedItems(row);
        return Order.restore(new Order.Snapshot(row.getUserId(), lines.stream().map(this::toDomainItem).toList(),
                voucher(row), row.getCurrency(), row.getStatus(), row.getPaymentStatus(),
                row.getMerchandiseSubtotalVnd(), row.getEligibleSubtotalVnd(), row.getDiscountVnd(),
                row.getShippingFeeVnd(), row.getTotalVnd()), row.getCreatedAt(), row.getPaymentExpiresAt());
    }

    public OrderItem toDomainItem(OrderItemJpaEntity row) {
        var line = new OrderLineSnapshot(row.getItemType(), row.getItemId(), row.getNameSnapshot(),
                row.getQuantity(), row.getUnitPriceVnd(), row.isVoucherEligible());
        return new OrderItem(line, row.getGrossAmountVnd(), row.getAllocatedDiscountVnd(), row.getNetAmountVnd());
    }

    public StoredOrder toStored(OrderJpaEntity row) {
        return new StoredOrder(row.getId(), row.getVersion(), row.getPaymentExpiresAt(), row.getCreatedAt(),
                row.getUpdatedAt(), toDomain(row), orderedItems(row).stream().map(i ->
                new StoredOrder.ItemIdentity(i.getId(), i.getLinePosition(), i.getItemType(), i.getItemId())).toList());
    }

    private List<OrderItemJpaEntity> orderedItems(OrderJpaEntity row) {
        var lines = row.getItems().stream().sorted(Comparator.comparingInt(OrderItemJpaEntity::getLinePosition)).toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getLinePosition() != i) throw new IllegalArgumentException("Invalid stored line positions");
        }
        return lines;
    }

    private Optional<OrderVoucher> voucher(OrderJpaEntity row) {
        if (row.getVoucherCode() == null && row.getVoucherKind() == null && row.getVoucherValue() == null
                && row.getVoucherMaxDiscountVnd() == null) return Optional.empty();
        if (row.getVoucherCode() == null || row.getVoucherKind() == null || row.getVoucherValue() == null
                || row.getVoucherMaxDiscountVnd() == null) {
            throw new IllegalArgumentException("Incomplete stored voucher snapshot");
        }
        return Optional.of(new OrderVoucher(row.getVoucherCode(), row.getVoucherKind(), row.getVoucherValue(),
                row.getVoucherMaxDiscountVnd()));
    }
}
