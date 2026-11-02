package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.entity.PaymentStatus;
import java.time.Instant;
import java.util.List;

/** Historical persisted Order and shipping snapshots. */
public record OrderDetail(Integer orderId, OrderStatus status, PaymentStatus paymentStatus,
                          String currency, Instant createdAt, Instant updatedAt, Instant paymentExpiresAt,
                          long merchandiseSubtotalVnd, long eligibleSubtotalVnd, long discountVnd,
                          long shippingFeeVnd, long totalVnd, VoucherSnapshot voucher,
                          List<Item> items, ShippingAddress shippingAddress) {
    public OrderDetail { items = List.copyOf(items); }

    public record VoucherSnapshot(String code, OrderVoucher.Kind kind, long value,
                                  long maximumDiscountVnd) { }
    public record Item(Integer orderItemId, int linePosition, OrderItemType itemType, Integer itemId,
                       String nameSnapshot, int quantity, long unitPriceVnd, boolean voucherEligible,
                       long grossAmountVnd, long allocatedDiscountVnd, long netAmountVnd) { }
    public record ShippingAddress(String recipient, String phone, String addressLine, String locality,
                                  String postalCode, String countryCode) { }
}