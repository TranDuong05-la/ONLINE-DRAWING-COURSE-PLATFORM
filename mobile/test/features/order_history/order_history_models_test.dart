import 'package:artnest_app/features/order_history/models/order_history_models.dart';
import 'package:artnest_app/features/order_history/utils/order_history_formatters.dart';
import 'package:flutter_test/flutter_test.dart';

import 'order_history_test_data.dart';

void main() {
  test('summary parses integer IDs, statuses, VND and UTC Instants', () {
    final summary = OrderSummary.fromJson(orderSummaryJson(27));
    expect(summary.orderId, 27);
    expect(summary.status, OrderStatus.pendingPayment);
    expect(summary.paymentStatus, OrderPaymentStatus.created);
    expect(summary.totalVnd, 202500);
    expect(summary.itemCount, 2);
    expect(summary.createdAt.isUtc, isTrue);
    expect(summary.paymentExpiresAt, DateTime.utc(2026, 10, 1, 0, 30));
  });

  test('nullable deadline, voucher and address remain null', () {
    final summary = OrderSummary.fromJson(orderSummaryJson(28, deadline: null));
    final detail = OrderDetail.fromJson(orderDetailJson(28, nullable: true));
    expect(summary.paymentExpiresAt, isNull);
    expect(detail.paymentExpiresAt, isNull);
    expect(detail.voucher, isNull);
    expect(detail.shippingAddress, isNull);
  });

  test('detail preserves historical item and allocated money snapshots', () {
    final detail = OrderDetail.fromJson(orderDetailJson(29));
    expect(detail.voucher?.kind, OrderVoucherKind.fixedVnd);
    expect(detail.items.single.itemType, OrderItemType.product);
    expect(detail.items.single.unitPriceVnd, 100000);
    expect(detail.items.single.grossAmountVnd, 200000);
    expect(detail.items.single.allocatedDiscountVnd, 10000);
    expect(detail.items.single.netAmountVnd, 190000);
    expect(detail.shippingAddress?.countryCode, 'VN');
  });

  test('missing required monetary fields and list content are rejected', () {
    final summary = orderSummaryJson(30)..remove('totalVnd');
    expect(
      () => OrderSummary.fromJson(summary),
      throwsA(isA<OrderHistoryFormatException>()),
    );
    final page = orderPageJson([], totalElements: 0)..remove('content');
    expect(
      () => PaginatedOrderHistory.fromJson(page),
      throwsA(isA<OrderHistoryFormatException>()),
    );
    final detail = orderDetailJson(30)..remove('totalVnd');
    expect(
      () => OrderDetail.fromJson(detail),
      throwsA(isA<OrderHistoryFormatException>()),
    );
  });

  test(
    'unknown enums, fractional money and non-UTC timestamps are rejected',
    () {
      final badStatus = orderSummaryJson(31)..['status'] = 'REFUNDED';
      expect(
        () => OrderSummary.fromJson(badStatus),
        throwsA(isA<OrderHistoryFormatException>()),
      );
      final fractionalTotal = orderSummaryJson(31)..['totalVnd'] = 12.5;
      expect(
        () => OrderSummary.fromJson(fractionalTotal),
        throwsA(isA<OrderHistoryFormatException>()),
      );
      final localTimestamp = orderSummaryJson(31)
        ..['createdAt'] = '2026-10-01T00:00:00';
      expect(
        () => OrderSummary.fromJson(localTimestamp),
        throwsA(isA<OrderHistoryFormatException>()),
      );
    },
  );

  test('course item quantity must remain exactly one', () {
    final json = orderDetailJson(32);
    final items = json['items'] as List<Map<String, dynamic>>;
    items.single['itemType'] = 'COURSE';
    items.single['quantity'] = 2;
    expect(
      () => OrderDetail.fromJson(json),
      throwsA(isA<OrderHistoryFormatException>()),
    );
  });

  test('VND formatting uses integer grouping without fractional digits', () {
    expect(formatOrderVnd(1234567), '1.234.567 ₫');
    expect(formatOrderVnd(0), '0 ₫');
  });
}
