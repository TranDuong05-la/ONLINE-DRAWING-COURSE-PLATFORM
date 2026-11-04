import 'dart:math';

import '../../core/constants/api_constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_exceptions.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import '../order_history/data/api_order_history_repository.dart';
import '../order_history/models/order_history_models.dart';
import 'cart_item.dart';

abstract interface class CourseCheckoutRepository {
  Future<int> createOrder({
    required CartSnapshot cart,
    required String idempotencyKey,
    String? voucherCode,
  });
  Future<CoursePaymentSession> createPaymentSession(int orderId);
  Future<OrderDetail> getOrder(int orderId);
  Future<Set<int>> refreshOwnedCourses();
}

abstract interface class ProductCheckoutRepository implements CourseCheckoutRepository {
  Future<int> createProductOrder({
    required CartSnapshot cart,
    required String idempotencyKey,
    required ProductShippingAddress address,
    String? voucherCode,
  });
}

class ProductShippingAddress {
  const ProductShippingAddress({
    required this.recipient,
    required this.phone,
    required this.addressLine,
    required this.locality,
    required this.countryCode,
    this.postalCode = '',
  });
  final String recipient;
  final String phone;
  final String addressLine;
  final String locality;
  final String countryCode;
  final String postalCode;
}

class CoursePaymentSession {
  const CoursePaymentSession({
    required this.orderId,
    required this.paymentUrl,
    required this.expiresAt,
    required this.reused,
  });

  final int orderId;
  final Uri paymentUrl;
  final DateTime expiresAt;
  final bool reused;

  factory CoursePaymentSession.fromJson(dynamic source) {
    if (source is! Map<String, dynamic>) {
      throw const FormatException('Payment session is malformed.');
    }
    final orderId = source['orderId'];
    final rawUrl = source['paymentUrl'];
    final rawExpiry = source['expiresAt'];
    final reused = source['reused'];
    if (orderId is! num ||
        orderId <= 0 ||
        rawUrl is! String ||
        rawExpiry is! String ||
        reused is! bool) {
      throw const FormatException('Payment session fields are invalid.');
    }
    final uri = Uri.tryParse(rawUrl);
    final expiry = DateTime.tryParse(rawExpiry)?.toUtc();
    if (uri == null ||
        uri.scheme != 'https' ||
        !uri.host.toLowerCase().endsWith('vnpayment.vn') ||
        expiry == null) {
      throw const FormatException('Payment URL or expiration is invalid.');
    }
    return CoursePaymentSession(
      orderId: orderId.toInt(),
      paymentUrl: uri,
      expiresAt: expiry,
      reused: reused,
    );
  }
}

class ApiCourseCheckoutRepository implements ProductCheckoutRepository {
  ApiCourseCheckoutRepository({
    ApiClient? apiClient,
    ApiOrderHistoryRepository? orders,
    LearningRepository? learning,
  }) : _api = apiClient ?? ApiClient(),
       _orders = orders ?? ApiOrderHistoryRepository(apiClient: apiClient),
       _learning = learning ?? LearningRepository(apiClient: apiClient);

  final ApiClient _api;
  final ApiOrderHistoryRepository _orders;
  final LearningRepository _learning;

  @override
  Future<int> createOrder({
    required CartSnapshot cart,
    required String idempotencyKey,
    String? voucherCode,
  }) async {
    if (!cart.canCheckout ||
        cart.items.isEmpty ||
        cart.items.any(
          (item) =>
              !item.isCourseCheckoutItem ||
              item.availability != CartItemAvailability.available ||
              item.quantity != 1,
        )) {
      throw const FormatException(
        'Only available Course and Combo items can be checked out.',
      );
    }
    final response = await _api.post<dynamic>(
      ApiConstants.courseCheckout,
      headers: {'Idempotency-Key': idempotencyKey},
      data: {
        'cartRevision': cart.revision,
        'selections': cart.items
            .map((item) => {'cartItemId': item.id})
            .toList(growable: false),
        if (voucherCode != null && voucherCode.trim().isNotEmpty)
          'voucherCode': voucherCode.trim(),
      },
      fromJsonT: (json) => json,
    );
    if (!response.success) {
      throw ApiException(message: response.message, statusCode: 200);
    }
    final data = response.data;
    if (data is! Map<String, dynamic> ||
        data['orderId'] is! num ||
        (data['orderId'] as num) <= 0) {
      throw const FormatException(
        'Checkout response did not contain a valid Order ID.',
      );
    }
    return (data['orderId'] as num).toInt();
  }

  @override
  Future<int> createProductOrder({
    required CartSnapshot cart,
    required String idempotencyKey,
    required ProductShippingAddress address,
    String? voucherCode,
  }) async {
    if (!cart.canCheckout || cart.items.isEmpty ||
        !cart.items.any((item) => item.type == CartItemType.product) ||
        cart.items.any((item) => item.availability != CartItemAvailability.available)) {
      throw const FormatException('Only available Product or mixed Cart items can be checked out here.');
    }
    final response = await _api.post<dynamic>(
      '/api/checkout',
      headers: {'Idempotency-Key': idempotencyKey},
      data: {
        'cartRevision': cart.revision,
        'selections': cart.items.map((item) => {
          'cartItemId': item.id,
          'quantity': item.quantity,
        }).toList(growable: false),
        if (voucherCode != null && voucherCode.trim().isNotEmpty) 'voucherCode': voucherCode.trim(),
        'address': {
          'recipient': address.recipient,
          'phone': address.phone,
          'addressLine': address.addressLine,
          'locality': address.locality,
          'postalCode': address.postalCode,
          'countryCode': address.countryCode,
        },
      },
      fromJsonT: (json) => json,
    );
    if (!response.success) throw ApiException(message: response.message, statusCode: 200);
    final data = response.data;
    if (data is! Map<String, dynamic> || data['orderId'] is! num || (data['orderId'] as num) <= 0) {
      throw const FormatException('Product Checkout response did not contain a valid Order ID.');
    }
    return (data['orderId'] as num).toInt();
  }

  @override
  Future<CoursePaymentSession> createPaymentSession(int orderId) async {
    if (orderId <= 0) {
      throw ArgumentError.value(orderId, 'orderId');
    }
    final response = await _api.post<CoursePaymentSession>(
      ApiConstants.paymentSession(orderId),
      fromJsonT: CoursePaymentSession.fromJson,
    );
    if (!response.success ||
        response.data == null ||
        response.data!.orderId != orderId) {
      throw ApiException(
        message: response.message.isEmpty
            ? 'Payment session was not created.'
            : response.message,
        statusCode: 200,
      );
    }
    return response.data!;
  }

  @override
  Future<OrderDetail> getOrder(int orderId) => _orders.getOrderDetail(orderId);

  @override
  Future<Set<int>> refreshOwnedCourses() async =>
      (await _learning.getMyCourses())
          .map((MyCourse course) => course.courseId)
          .toSet();
}

String newCheckoutIdempotencyKey() {
  const hex = '0123456789abcdef';
  final random = Random.secure();
  final chars = List.generate(32, (_) => hex[random.nextInt(hex.length)]);
  chars.insert(12, '4');
  chars[16] = hex[(int.parse(chars[16], radix: 16) & 0x3) | 0x8];
  return 'mobile-${chars.join()}';
}
