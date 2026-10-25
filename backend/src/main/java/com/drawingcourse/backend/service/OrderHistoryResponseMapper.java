package com.drawingcourse.backend.service;

import com.drawingcourse.backend.dto.OrderDetail;
import com.drawingcourse.backend.dto.OrderSummary;
import com.drawingcourse.backend.repository.OrderHistoryProjection;
import java.util.ArrayList;
import java.util.List;

/** Maps persisted snapshots only; it has no catalog or pricing dependencies. */
public final class OrderHistoryResponseMapper {
    public OrderSummary toSummary(OrderHistoryProjection row) {
        return new OrderSummary(row.getOrderId(), row.getStatus(), row.getPaymentStatus(), row.getCreatedAt(),
                row.getUpdatedAt(), row.getPaymentExpiresAt(), row.getTotalVnd(), row.getCurrency(),
                row.getItemCount());
    }

    public OrderDetail toDetail(StoredOrder stored, OrderDetail.ShippingAddress address) {
        var order = stored.snapshot();
        var identities = stored.itemIdentities();
        var snapshots = order.getItems();
        if (identities.size() != snapshots.size()) throw new IllegalStateException("Stored Order item identities do not match snapshots");
        List<OrderDetail.Item> items = new ArrayList<>(snapshots.size());
        for (int i = 0; i < snapshots.size(); i++) {
            var identity = identities.get(i);
            var snapshot = snapshots.get(i);
            var line = snapshot.catalogSnapshot();
            if (identity.linePosition() != i || !identity.itemType().equals(line.itemType())
                    || !identity.itemId().equals(line.itemId())) {
                throw new IllegalStateException("Stored Order item identity mismatch");
            }
            items.add(new OrderDetail.Item(identity.id(), identity.linePosition(), line.itemType(), line.itemId(),
                    line.name(), line.quantity(), line.unitPriceVnd(), line.voucherEligible(),
                    snapshot.grossAmountVnd(), snapshot.allocatedDiscountVnd(), snapshot.netAmountVnd()));
        }
        var voucher = order.getVoucher().map(v -> new OrderDetail.VoucherSnapshot(v.code(), v.kind(), v.value(),
                v.maximumDiscountVnd())).orElse(null);
        return new OrderDetail(stored.id(), order.getStatus(), order.getPaymentStatus(), order.getCurrency(),
                stored.createdAt(), stored.updatedAt(), stored.paymentExpiresAt(), order.getMerchandiseSubtotalVnd(),
                order.getEligibleSubtotalVnd(), order.getDiscountVnd(), order.getShippingFeeVnd(), order.getTotalVnd(),
                voucher, items, address);
    }
}