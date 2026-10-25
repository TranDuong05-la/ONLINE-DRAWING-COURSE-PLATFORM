import 'package:artnest_app/features/order_history/models/order_history_models.dart';

Map<String, dynamic> orderSummaryJson(
  int id, {
  String status = 'PENDING_PAYMENT',
  String? deadline = '2026-10-01T00:30:00Z',
}) => {
  'orderId': id,
  'status': status,
  'paymentStatus': 'CREATED',
  'createdAt': '2026-10-01T00:00:00Z',
  'updatedAt': '2026-10-01T00:00:00Z',
  'paymentExpiresAt': deadline,
  'totalVnd': 202500,
  'currency': 'VND',
  'itemCount': 2,
};

Map<String, dynamic> orderDetailJson(int id, {bool nullable = false}) => {
  'orderId': id,
  'status': 'PENDING_PAYMENT',
  'paymentStatus': 'CREATED',
  'currency': 'VND',
  'createdAt': '2026-10-01T00:00:00Z',
  'updatedAt': '2026-10-01T00:00:00Z',
  'paymentExpiresAt': nullable ? null : '2026-10-01T00:30:00Z',
  'merchandiseSubtotalVnd': 200000,
  'eligibleSubtotalVnd': 200000,
  'discountVnd': 10000,
  'shippingFeeVnd': 2500,
  'totalVnd': 192500,
  'voucher': nullable
      ? null
      : {
          'code': 'HISTORY-10K',
          'kind': 'FIXED_VND',
          'value': 10000,
          'maximumDiscountVnd': 10000,
        },
  'items': [
    {
      'orderItemId': 5,
      'linePosition': 0,
      'itemType': 'PRODUCT',
      'itemId': 31,
      'nameSnapshot': 'Historical brush set',
      'quantity': 2,
      'unitPriceVnd': 100000,
      'voucherEligible': true,
      'grossAmountVnd': 200000,
      'allocatedDiscountVnd': 10000,
      'netAmountVnd': 190000,
    },
  ],
  'shippingAddress': nullable
      ? null
      : {
          'recipient': 'Test recipient',
          'phone': '0900000000',
          'addressLine': 'Street 1',
          'locality': 'Ward 2',
          'postalCode': '700000',
          'countryCode': 'VN',
        },
};

Map<String, dynamic> orderPageJson(
  List<Map<String, dynamic>> orders, {
  int number = 0,
  int totalPages = 1,
  int totalElements = 0,
  int size = 20,
  bool? last,
}) => {
  'content': orders,
  'totalElements': totalElements,
  'totalPages': totalPages,
  'size': size,
  'number': number,
  'first': number == 0,
  'last': last ?? number + 1 >= totalPages,
  'empty': orders.isEmpty,
};

PaginatedOrderHistory parsedPage(
  List<int> ids, {
  int number = 0,
  int totalPages = 1,
  int? totalElements,
  bool? last,
}) => PaginatedOrderHistory.fromJson(
  orderPageJson(
    ids.map(orderSummaryJson).toList(),
    number: number,
    totalPages: totalPages,
    totalElements: totalElements ?? ids.length,
    last: last,
  ),
);
