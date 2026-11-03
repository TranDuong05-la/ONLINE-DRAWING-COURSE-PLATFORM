import 'dart:convert';
import 'dart:typed_data';

import 'package:artnest_app/core/network/api_client.dart';
import 'package:artnest_app/core/storage/token_storage.dart';
import 'package:artnest_app/features/cart/api_course_cart_repository.dart';
import 'package:artnest_app/features/cart/cart_item.dart';
import 'package:artnest_app/features/cart/course_checkout_repository.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dio/dio.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _RecordingAdapter adapter;
  late ApiClient api;

  setUp(() async {
    FlutterSecureStorage.setMockInitialValues({
      'artnest_access_token': 'test-token',
    });
    api = ApiClient(tokenStorage: TokenStorage());
    adapter = _RecordingAdapter();
    api.dio.httpClientAdapter = adapter;
  });

  test(
    'Cart add uses the Course-only route and sends no client price or user ID',
    () async {
      adapter.respond = (request) => _success(_cartResponse);
      final repository = ApiCourseCartRepository(apiClient: api);
      final snapshot = await repository.addCourse(101);

      expect(snapshot.items.single.quantity, 1);
      final request = adapter.requests.single;
      expect(request.method, 'POST');
      expect(request.path, '/api/cart/items');
      expect(request.data, {
        'itemType': 'COURSE',
        'itemId': 101,
        'quantity': 1,
      });
      expect((request.data as Map).containsKey('price'), isFalse);
      expect((request.data as Map).containsKey('userId'), isFalse);
    },
  );

  test('Checkout uses revision, cart item IDs, voucher and idempotency header; payment URL follows persisted Order', () async {
    adapter.respond = (request) {
      if (request.path == '/api/checkout/course-only') {
        return _success({'orderId': 77, 'replayed': false});
      }
      if (request.path == '/api/orders/77/payment-session') {
        return _success({
          'orderId': 77,
          'paymentUrl': 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_SecureHash=synthetic',
          'expiresAt': '2026-11-03T12:30:00Z',
          'reused': true,
        });
      }
      throw StateError('Unexpected route ${request.path}');
    };
    final repository = ApiCourseCheckoutRepository(apiClient: api);
    final orderId = await repository.createOrder(
      cart: _cart(),
      idempotencyKey: 'mobile-test-key',
      voucherCode: 'ART25',
    );
    final session = await repository.createPaymentSession(orderId);

    expect(orderId, 77);
    expect(adapter.requests[0].path, '/api/checkout/course-only');
    expect(adapter.requests[0].headers['Idempotency-Key'], 'mobile-test-key');
    expect(adapter.requests[0].data, {
      'cartRevision': 8,
      'selections': [
        {'cartItemId': 30},
      ],
      'voucherCode': 'ART25',
    });
    expect(adapter.requests[1].path, '/api/orders/77/payment-session');
    expect(session.paymentUrl.host, 'sandbox.vnpayment.vn');
    expect(session.orderId, 77);
    expect(session.reused, isTrue);
  });
}

const _cartResponse = {
  'cartId': 5,
  'revision': 8,
  'items': [
    {
      'cartItemId': 30,
      'itemType': 'COURSE',
      'itemId': 101,
      'quantity': 1,
      'displayName': 'Painting',
      'availabilityStatus': 'AVAILABLE',
      'unitPriceVnd': 240000,
      'lineTotalVnd': 240000,
      'availabilityMessage': null,
    },
  ],
  'subtotalVnd': 240000,
  'currency': 'VND',
  'hasUnavailableItems': false,
  'canCheckout': true,
  'availabilityWarnings': [],
};

CartSnapshot _cart() => CartSnapshot.fromJson(_cartResponse);

Map<String, dynamic> _success(dynamic data) => {
  'success': true,
  'message': 'ok',
  'data': data,
};

class _RecordingAdapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  late Map<String, dynamic> Function(RequestOptions request) respond;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    return ResponseBody.fromString(
      jsonEncode(respond(options)),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
