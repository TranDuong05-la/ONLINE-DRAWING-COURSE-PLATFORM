import 'dart:convert';
import 'dart:typed_data';

import 'package:artnest_app/core/network/api_client.dart';
import 'package:artnest_app/core/storage/token_storage.dart';
import 'package:artnest_app/features/cart/api_product_cart_repository.dart';
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
    FlutterSecureStorage.setMockInitialValues({'artnest_access_token': 'test-token'});
    api = ApiClient(tokenStorage: TokenStorage());
    adapter = _RecordingAdapter();
    api.dio.httpClientAdapter = adapter;
  });

  test('Product Cart quantity uses the general authenticated Cart route', () async {
    adapter.respond = (_) => _success(_productCartResponse(quantity: 3));
    final cart = await ApiProductCartRepository(apiClient: api).setProductQuantity(44, 3);

    expect(cart.items.single.quantity, 3);
    expect(adapter.requests.single.method, 'PATCH');
    expect(adapter.requests.single.path, '/api/cart/items/44');
    expect(adapter.requests.single.data, {'quantity': 3});
  });

  test('Product Checkout sends server-priced cart identities, address and idempotency key', () async {
    adapter.respond = (request) {
      expect(request.path, '/api/checkout');
      return _success({'orderId': 91, 'replayed': false});
    };
    final repository = ApiCourseCheckoutRepository(apiClient: api);
    final orderId = await repository.createProductOrder(
      cart: CartSnapshot.fromJson(_productCartResponse(quantity: 2)),
      idempotencyKey: 'mobile-product-test',
      voucherCode: 'DEMO10',
      address: const ProductShippingAddress(
        recipient: 'Demo Buyer',
        phone: '0900000000',
        addressLine: '10 Demo Street',
        locality: 'Hanoi',
        countryCode: 'VN',
        postalCode: '100000',
      ),
    );

    expect(orderId, 91);
    final request = adapter.requests.single;
    expect(request.headers['Idempotency-Key'], 'mobile-product-test');
    expect(request.data, {
      'cartRevision': 12,
      'selections': [
        {'cartItemId': 44, 'quantity': 2},
      ],
      'voucherCode': 'DEMO10',
      'address': {
        'recipient': 'Demo Buyer',
        'phone': '0900000000',
        'addressLine': '10 Demo Street',
        'locality': 'Hanoi',
        'postalCode': '100000',
        'countryCode': 'VN',
      },
    });
    expect((request.data as Map).containsKey('userId'), isFalse);
    expect((request.data as Map).containsKey('price'), isFalse);
  });

  test('Product Checkout rejects a Course-only cart', () async {
    final repository = ApiCourseCheckoutRepository(apiClient: api);
    await expectLater(
      repository.createProductOrder(
        cart: CartSnapshot(
          revision: 1,
          items: [CartItem(id: 3, itemId: 8, type: CartItemType.course, name: 'Course', unitPriceVnd: 1000)],
          subtotalVnd: 1000,
          canCheckout: true,
          hasUnavailableItems: false,
        ),
        idempotencyKey: 'reject-course-only',
        address: const ProductShippingAddress(
          recipient: 'Buyer', phone: '0900000000', addressLine: 'Street', locality: 'City', countryCode: 'VN'),
      ),
      throwsFormatException,
    );
    expect(adapter.requests, isEmpty);
  });
}

Map<String, dynamic> _productCartResponse({required int quantity}) => {
  'cartId': 5,
  'revision': 12,
  'items': [
    {
      'cartItemId': 44,
      'itemType': 'PRODUCT',
      'itemId': 77,
      'quantity': quantity,
      'displayName': 'Brush Set',
      'availabilityStatus': 'AVAILABLE',
      'unitPriceVnd': 125000,
      'lineTotalVnd': 125000 * quantity,
      'availabilityMessage': null,
    },
  ],
  'subtotalVnd': 125000 * quantity,
  'currency': 'VND',
  'hasUnavailableItems': false,
  'canCheckout': true,
  'availabilityWarnings': [],
};

Map<String, dynamic> _success(dynamic data) => {'success': true, 'message': 'ok', 'data': data};

class _RecordingAdapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  late Map<String, dynamic> Function(RequestOptions request) respond;

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream,
      Future<void>? cancelFuture) async {
    requests.add(options);
    return ResponseBody.fromString(jsonEncode(respond(options)), 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }

  @override
  void close({bool force = false}) {}
}
