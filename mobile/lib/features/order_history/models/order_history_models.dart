import '../../../models/page_response.dart';

class OrderHistoryFormatException implements Exception {
  const OrderHistoryFormatException(this.message);
  final String message;
  @override
  String toString() => message;
}

Map<String, dynamic> _object(dynamic value, String field) {
  if (value is Map<String, dynamic>) return value;
  throw OrderHistoryFormatException('$field must be an object.');
}

Object? _required(Map<String, dynamic> json, String key, String parent) {
  if (!json.containsKey(key) || json[key] == null) {
    throw OrderHistoryFormatException('$parent.$key is required.');
  }
  return json[key];
}

int _integer(Map<String, dynamic> json, String key, String parent) {
  final value = _required(json, key, parent);
  if (value is int) return value;
  throw OrderHistoryFormatException('$parent.$key must be an integer.');
}

String _string(Map<String, dynamic> json, String key, String parent) {
  final value = _required(json, key, parent);
  if (value is String && value.trim().isNotEmpty) return value;
  throw OrderHistoryFormatException('$parent.$key must be a non-empty string.');
}

bool _boolean(Map<String, dynamic> json, String key, String parent) {
  final value = _required(json, key, parent);
  if (value is bool) return value;
  throw OrderHistoryFormatException('$parent.$key must be a boolean.');
}

DateTime _utcInstant(Map<String, dynamic> json, String key, String parent) {
  final value = _string(json, key, parent);
  if (!value.endsWith('Z')) {
    throw OrderHistoryFormatException(
      '$parent.$key must be a UTC ISO-8601 instant.',
    );
  }
  try {
    final parsed = DateTime.parse(value);
    if (!parsed.isUtc) throw const FormatException();
    return parsed;
  } on FormatException {
    throw OrderHistoryFormatException(
      '$parent.$key is not a valid UTC instant.',
    );
  }
}

DateTime? _nullableUtcInstant(
  Map<String, dynamic> json,
  String key,
  String parent,
) {
  final value = json[key];
  if (value == null) return null;
  if (value is! String || !value.endsWith('Z')) {
    throw OrderHistoryFormatException(
      '$parent.$key must be null or a UTC ISO-8601 instant.',
    );
  }
  try {
    final parsed = DateTime.parse(value);
    if (!parsed.isUtc) throw const FormatException();
    return parsed;
  } on FormatException {
    throw OrderHistoryFormatException(
      '$parent.$key is not a valid UTC instant.',
    );
  }
}

String _wireName(Enum value) => value.name
    .replaceAllMapped(
      RegExp(r'([a-z0-9])([A-Z])'),
      (match) => '${match[1]}_${match[2]}',
    )
    .toUpperCase();

extension OrderHistoryEnumWireName on Enum {
  String get wireName => _wireName(this);
}

T _enumValue<T extends Enum>(List<T> values, String raw, String field) {
  for (final value in values) {
    if (_wireName(value) == raw) return value;
  }
  throw OrderHistoryFormatException('$field has an unsupported value.');
}

enum OrderStatus { pendingPayment, confirmed, completed, cancelled, expired }

enum OrderPaymentStatus {
  created,
  pending,
  succeeded,
  failed,
  cancelled,
  expired,
  unknown,
}

enum OrderItemType { course, product }

enum OrderVoucherKind { fixedVnd, percentBasisPoints }

class OrderSummary {
  const OrderSummary({
    required this.orderId,
    required this.status,
    required this.paymentStatus,
    required this.createdAt,
    required this.updatedAt,
    required this.paymentExpiresAt,
    required this.totalVnd,
    required this.currency,
    required this.itemCount,
  });

  final int orderId;
  final OrderStatus status;
  final OrderPaymentStatus paymentStatus;
  final DateTime createdAt;
  final DateTime updatedAt;
  final DateTime? paymentExpiresAt;
  final int totalVnd;
  final String currency;
  final int itemCount;

  factory OrderSummary.fromJson(dynamic source) {
    final json = _object(source, 'OrderSummary');
    final id = _integer(json, 'orderId', 'OrderSummary');
    final currency = _string(json, 'currency', 'OrderSummary');
    if (currency != 'VND') {
      throw const OrderHistoryFormatException(
        'OrderSummary.currency must be VND.',
      );
    }
    final total = _integer(json, 'totalVnd', 'OrderSummary');
    final count = _integer(json, 'itemCount', 'OrderSummary');
    if (id <= 0 || total < 0 || count < 0) {
      throw const OrderHistoryFormatException(
        'OrderSummary contains an invalid ID, total, or item count.',
      );
    }
    return OrderSummary(
      orderId: id,
      status: _enumValue(
        OrderStatus.values,
        _string(json, 'status', 'OrderSummary'),
        'status',
      ),
      paymentStatus: _enumValue(
        OrderPaymentStatus.values,
        _string(json, 'paymentStatus', 'OrderSummary'),
        'paymentStatus',
      ),
      createdAt: _utcInstant(json, 'createdAt', 'OrderSummary'),
      updatedAt: _utcInstant(json, 'updatedAt', 'OrderSummary'),
      paymentExpiresAt: _nullableUtcInstant(
        json,
        'paymentExpiresAt',
        'OrderSummary',
      ),
      totalVnd: total,
      currency: currency,
      itemCount: count,
    );
  }
}

class OrderVoucherSnapshot {
  const OrderVoucherSnapshot({
    required this.code,
    required this.kind,
    required this.value,
    required this.maximumDiscountVnd,
  });
  final String code;
  final OrderVoucherKind kind;
  final int value;
  final int maximumDiscountVnd;

  factory OrderVoucherSnapshot.fromJson(dynamic source) {
    final json = _object(source, 'voucher');
    final value = _integer(json, 'value', 'voucher');
    final maximum = _integer(json, 'maximumDiscountVnd', 'voucher');
    if (value < 0 || maximum < 0) {
      throw const OrderHistoryFormatException(
        'Voucher values cannot be negative.',
      );
    }
    return OrderVoucherSnapshot(
      code: _string(json, 'code', 'voucher'),
      kind: _enumValue(
        OrderVoucherKind.values,
        _string(json, 'kind', 'voucher'),
        'voucher.kind',
      ),
      value: value,
      maximumDiscountVnd: maximum,
    );
  }
}

class OrderShippingAddress {
  const OrderShippingAddress({
    required this.recipient,
    required this.phone,
    required this.addressLine,
    required this.locality,
    required this.postalCode,
    required this.countryCode,
  });
  final String recipient;
  final String phone;
  final String addressLine;
  final String locality;
  final String postalCode;
  final String countryCode;

  factory OrderShippingAddress.fromJson(dynamic source) {
    final json = _object(source, 'shippingAddress');
    return OrderShippingAddress(
      recipient: _string(json, 'recipient', 'shippingAddress'),
      phone: _string(json, 'phone', 'shippingAddress'),
      addressLine: _string(json, 'addressLine', 'shippingAddress'),
      locality: _string(json, 'locality', 'shippingAddress'),
      postalCode: _string(json, 'postalCode', 'shippingAddress'),
      countryCode: _string(json, 'countryCode', 'shippingAddress'),
    );
  }
}

class OrderItem {
  const OrderItem({
    required this.orderItemId,
    required this.linePosition,
    required this.itemType,
    required this.itemId,
    required this.nameSnapshot,
    required this.quantity,
    required this.unitPriceVnd,
    required this.voucherEligible,
    required this.grossAmountVnd,
    required this.allocatedDiscountVnd,
    required this.netAmountVnd,
  });
  final int orderItemId;
  final int linePosition;
  final OrderItemType itemType;
  final int itemId;
  final String nameSnapshot;
  final int quantity;
  final int unitPriceVnd;
  final bool voucherEligible;
  final int grossAmountVnd;
  final int allocatedDiscountVnd;
  final int netAmountVnd;

  factory OrderItem.fromJson(dynamic source) {
    final json = _object(source, 'OrderItem');
    final item = OrderItem(
      orderItemId: _integer(json, 'orderItemId', 'OrderItem'),
      linePosition: _integer(json, 'linePosition', 'OrderItem'),
      itemType: _enumValue(
        OrderItemType.values,
        _string(json, 'itemType', 'OrderItem'),
        'itemType',
      ),
      itemId: _integer(json, 'itemId', 'OrderItem'),
      nameSnapshot: _string(json, 'nameSnapshot', 'OrderItem'),
      quantity: _integer(json, 'quantity', 'OrderItem'),
      unitPriceVnd: _integer(json, 'unitPriceVnd', 'OrderItem'),
      voucherEligible: _boolean(json, 'voucherEligible', 'OrderItem'),
      grossAmountVnd: _integer(json, 'grossAmountVnd', 'OrderItem'),
      allocatedDiscountVnd: _integer(json, 'allocatedDiscountVnd', 'OrderItem'),
      netAmountVnd: _integer(json, 'netAmountVnd', 'OrderItem'),
    );
    if (item.orderItemId <= 0 ||
        item.linePosition < 0 ||
        item.itemId <= 0 ||
        item.quantity <= 0 ||
        (item.itemType == OrderItemType.course && item.quantity != 1) ||
        item.unitPriceVnd < 0 ||
        item.grossAmountVnd < 0 ||
        item.allocatedDiscountVnd < 0 ||
        item.netAmountVnd < 0) {
      throw const OrderHistoryFormatException(
        'OrderItem contains invalid persisted values.',
      );
    }
    return item;
  }
}

class OrderDetail {
  OrderDetail({
    required this.orderId,
    required this.status,
    required this.paymentStatus,
    required this.currency,
    required this.createdAt,
    required this.updatedAt,
    required this.paymentExpiresAt,
    required this.merchandiseSubtotalVnd,
    required this.eligibleSubtotalVnd,
    required this.discountVnd,
    required this.shippingFeeVnd,
    required this.totalVnd,
    required this.voucher,
    required this.items,
    required this.shippingAddress,
  });
  final int orderId;
  final OrderStatus status;
  final OrderPaymentStatus paymentStatus;
  final String currency;
  final DateTime createdAt;
  final DateTime updatedAt;
  final DateTime? paymentExpiresAt;
  final int merchandiseSubtotalVnd;
  final int eligibleSubtotalVnd;
  final int discountVnd;
  final int shippingFeeVnd;
  final int totalVnd;
  final OrderVoucherSnapshot? voucher;
  final List<OrderItem> items;
  final OrderShippingAddress? shippingAddress;

  factory OrderDetail.fromJson(dynamic source) {
    final json = _object(source, 'OrderDetail');
    final currency = _string(json, 'currency', 'OrderDetail');
    if (currency != 'VND') {
      throw const OrderHistoryFormatException(
        'OrderDetail.currency must be VND.',
      );
    }
    final rawItems = _required(json, 'items', 'OrderDetail');
    if (rawItems is! List || rawItems.isEmpty) {
      throw const OrderHistoryFormatException(
        'OrderDetail.items must be a non-empty list.',
      );
    }
    final detail = OrderDetail(
      orderId: _integer(json, 'orderId', 'OrderDetail'),
      status: _enumValue(
        OrderStatus.values,
        _string(json, 'status', 'OrderDetail'),
        'status',
      ),
      paymentStatus: _enumValue(
        OrderPaymentStatus.values,
        _string(json, 'paymentStatus', 'OrderDetail'),
        'paymentStatus',
      ),
      currency: currency,
      createdAt: _utcInstant(json, 'createdAt', 'OrderDetail'),
      updatedAt: _utcInstant(json, 'updatedAt', 'OrderDetail'),
      paymentExpiresAt: _nullableUtcInstant(
        json,
        'paymentExpiresAt',
        'OrderDetail',
      ),
      merchandiseSubtotalVnd: _integer(
        json,
        'merchandiseSubtotalVnd',
        'OrderDetail',
      ),
      eligibleSubtotalVnd: _integer(json, 'eligibleSubtotalVnd', 'OrderDetail'),
      discountVnd: _integer(json, 'discountVnd', 'OrderDetail'),
      shippingFeeVnd: _integer(json, 'shippingFeeVnd', 'OrderDetail'),
      totalVnd: _integer(json, 'totalVnd', 'OrderDetail'),
      voucher: json['voucher'] == null
          ? null
          : OrderVoucherSnapshot.fromJson(json['voucher']),
      items: List<OrderItem>.unmodifiable(rawItems.map(OrderItem.fromJson)),
      shippingAddress: json['shippingAddress'] == null
          ? null
          : OrderShippingAddress.fromJson(json['shippingAddress']),
    );
    if (detail.orderId <= 0 ||
        detail.merchandiseSubtotalVnd < 0 ||
        detail.eligibleSubtotalVnd < 0 ||
        detail.discountVnd < 0 ||
        detail.shippingFeeVnd < 0 ||
        detail.totalVnd < 0 ||
        detail.items.isEmpty) {
      throw const OrderHistoryFormatException(
        'OrderDetail contains invalid persisted values.',
      );
    }
    for (var i = 0; i < detail.items.length; i++) {
      if (detail.items[i].linePosition != i) {
        throw const OrderHistoryFormatException(
          'OrderDetail.items are not in persisted line order.',
        );
      }
    }
    return detail;
  }
}

class PaginatedOrderHistory {
  const PaginatedOrderHistory(this.page);
  final PageResponse<OrderSummary> page;

  List<OrderSummary> get content => page.content;
  int get totalElements => page.totalElements;
  int get totalPages => page.totalPages;
  int get size => page.size;
  int get number => page.number;
  bool get first => page.first;
  bool get last => page.last;
  bool get empty => page.empty;

  factory PaginatedOrderHistory.fromJson(dynamic source) {
    final json = _object(source, 'PageResponse');
    final rawContent = _required(json, 'content', 'PageResponse');
    if (rawContent is! List) {
      throw const OrderHistoryFormatException(
        'PageResponse.content must be a list.',
      );
    }
    final totalElements = _integer(json, 'totalElements', 'PageResponse');
    final totalPages = _integer(json, 'totalPages', 'PageResponse');
    final size = _integer(json, 'size', 'PageResponse');
    final number = _integer(json, 'number', 'PageResponse');
    _boolean(json, 'first', 'PageResponse');
    _boolean(json, 'last', 'PageResponse');
    final empty = _boolean(json, 'empty', 'PageResponse');
    if (totalElements < 0 ||
        totalPages < 0 ||
        size < 1 ||
        size > 100 ||
        number < 0 ||
        rawContent.length > size ||
        empty != rawContent.isEmpty) {
      throw const OrderHistoryFormatException(
        'PageResponse metadata is inconsistent.',
      );
    }
    final page = PageResponse<OrderSummary>.fromJson(
      json,
      OrderSummary.fromJson,
    );
    if (page.content.any((order) => order.orderId <= 0)) {
      throw const OrderHistoryFormatException(
        'PageResponse contains an invalid Order ID.',
      );
    }
    return PaginatedOrderHistory(page);
  }
}
