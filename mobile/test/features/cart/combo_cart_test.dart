import 'dart:convert';
import 'dart:typed_data';

import 'package:artnest_app/core/network/api_client.dart';
import 'package:artnest_app/core/storage/token_storage.dart';
import 'package:artnest_app/features/cart/api_course_cart_repository.dart';
import 'package:artnest_app/features/cart/cart_item.dart';
import 'package:artnest_app/features/cart/cart_screen.dart';
import 'package:artnest_app/features/cart/course_checkout_repository.dart';
import 'package:artnest_app/features/cart/mock_cart_repository.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> _comboLine({
  int id = 41,
  String availability = 'AVAILABLE',
  int quantity = 1,
}) => {
  'cartItemId': id,
  'itemType': 'COMBO',
  'itemId': 7,
  'quantity': quantity,
  'displayName': 'Watercolor + Portrait combo',
  'availabilityStatus': availability,
  'unitPriceVnd': availability == 'AVAILABLE' ? 799000 : null,
  'lineTotalVnd': availability == 'AVAILABLE' ? 799000 : null,
  'availabilityMessage': availability == 'AVAILABLE'
      ? null
      : 'This item covers a Course that is already in your Cart. Remove one of them.',
};

Map<String, dynamic> _cartJson(List<Map<String, dynamic>> items) {
  final available = items.where((i) => i['availabilityStatus'] == 'AVAILABLE');
  final subtotal = available.fold<int>(
    0,
    (sum, i) => sum + (i['lineTotalVnd'] as int),
  );
  final unavailable = items.length != available.length;
  return {
    'cartId': 3,
    'revision': 5,
    'items': items,
    'subtotalVnd': subtotal,
    'currency': 'VND',
    'hasUnavailableItems': unavailable,
    'canCheckout': items.isNotEmpty && !unavailable,
    'availabilityWarnings': [],
  };
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Combo cart model', () {
    test('parses a Combo as one line with the server price', () {
      final cart = CartSnapshot.fromJson(_cartJson([_comboLine()]));

      final item = cart.items.single;
      expect(item.type, CartItemType.combo);
      expect(item.isCourseCheckoutItem, isTrue);
      expect(item.name, 'Watercolor + Portrait combo');
      expect(item.quantity, 1);
      expect(item.unitPriceVnd, 799000);
      expect(cart.subtotalVnd, 799000);
      expect(cart.canCheckout, isTrue);
    });

    test('a Combo line is never more than one', () {
      expect(
        () => CartSnapshot.fromJson(_cartJson([_comboLine(quantity: 2)])),
        throwsFormatException,
      );
      expect(
        () => CartItem(
          id: 1,
          itemId: 7,
          type: CartItemType.combo,
          name: 'Combo',
          unitPriceVnd: 1000,
          quantity: 2,
        ),
        throwsArgumentError,
      );
    });

    test('an overlapping Combo comes back unavailable and blocks checkout', () {
      final cart = CartSnapshot.fromJson(
        _cartJson([_comboLine(availability: 'UNAVAILABLE')]),
      );

      expect(cart.items.single.availability, CartItemAvailability.unavailable);
      expect(cart.items.single.unitPriceVnd, isNull);
      expect(cart.hasUnavailableItems, isTrue);
      expect(cart.canCheckout, isFalse);
    });

    test('products are still not Course-checkout items', () {
      final product = CartItem(
        id: 2,
        itemId: 9,
        type: CartItemType.product,
        name: 'Brush set',
        unitPriceVnd: 1000,
      );
      expect(product.isCourseCheckoutItem, isFalse);
    });
  });

  group('Combo cart screen', () {
    Future<void> showCart(WidgetTester tester, MockCartRepository repo) async {
      await tester.pumpWidget(
        MaterialApp(
          home: CartScreen(repository: repo, purchaseEnabled: true),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('shows the Combo as a line and allows checkout', (tester) async {
      await showCart(
        tester,
        MockCartRepository(
          initialItems: [
            CartItem(
              id: 1,
              itemId: 7,
              type: CartItemType.combo,
              name: 'Watercolor + Portrait combo',
              unitPriceVnd: 799000,
            ),
          ],
        ),
      );

      expect(find.text('Watercolor + Portrait combo'), findsOneWidget);
      expect(find.textContaining('Combo · quantity 1'), findsOneWidget);
      expect(find.textContaining('Product purchases are not supported'), findsNothing);
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey('start-course-checkout')),
            )
            .onPressed,
        isNotNull,
      );
    });

    testWidgets('a Combo plus a Product needs Product purchasing enabled in this build', (
      tester,
    ) async {
      await showCart(
        tester,
        MockCartRepository(
          initialItems: [
            CartItem(
              id: 1,
              itemId: 7,
              type: CartItemType.combo,
              name: 'A combo',
              unitPriceVnd: 799000,
            ),
            CartItem(
              id: 2,
              itemId: 9,
              type: CartItemType.product,
              name: 'Brush set',
              unitPriceVnd: 50000,
            ),
          ],
        ),
      );

      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey('start-course-checkout')),
            )
            .onPressed,
        isNull,
      );
    });
  });

  group('Combo API', () {
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

    test('addCombo sends only the Combo identity and quantity one', () async {
      adapter.respond = (request) => {
        'success': true,
        'message': 'ok',
        'data': _cartJson([_comboLine()]),
      };

      final snapshot = await ApiCourseCartRepository(apiClient: api).addCombo(7);

      expect(snapshot.items.single.type, CartItemType.combo);
      final request = adapter.requests.single;
      expect(request.method, 'POST');
      expect(request.path, '/api/cart/items');
      expect(request.data, {'itemType': 'COMBO', 'itemId': 7, 'quantity': 1});
      expect((request.data as Map).containsKey('price'), isFalse);
      expect((request.data as Map).containsKey('userId'), isFalse);
    });

    test('checkout submits a Combo cart line like any Course line', () async {
      adapter.respond = (request) => {
        'success': true,
        'message': 'ok',
        'data': {'orderId': 88, 'replayed': false},
      };
      final cart = CartSnapshot.fromJson(_cartJson([_comboLine()]));

      final orderId = await ApiCourseCheckoutRepository(apiClient: api)
          .createOrder(cart: cart, idempotencyKey: 'combo-key');

      expect(orderId, 88);
      expect(adapter.requests.single.path, '/api/checkout/course-only');
      expect(adapter.requests.single.headers['Idempotency-Key'], 'combo-key');
      expect(adapter.requests.single.data['selections'], [
        {'cartItemId': 41},
      ]);
    });

    test('a Combo and a Product go through one Product checkout with the shipping address', () async {
      adapter.respond = (request) => {
        'success': true,
        'message': 'ok',
        'data': {'orderId': 91, 'replayed': false},
      };
      final cart = CartSnapshot(
        revision: 6,
        items: [
          CartItem(
            id: 41,
            itemId: 7,
            type: CartItemType.combo,
            name: 'Watercolor + Portrait combo',
            unitPriceVnd: 799000,
          ),
          CartItem(
            id: 42,
            itemId: 9,
            type: CartItemType.product,
            name: 'Brush set',
            unitPriceVnd: 50000,
            quantity: 2,
          ),
        ],
        subtotalVnd: 899000,
        canCheckout: true,
        hasUnavailableItems: false,
      );

      final orderId = await ApiCourseCheckoutRepository(apiClient: api).createProductOrder(
        cart: cart,
        idempotencyKey: 'mixed-key',
        address: const ProductShippingAddress(
          recipient: 'Buyer',
          phone: '0900000000',
          addressLine: '1 Street',
          locality: 'District 1',
          countryCode: 'VN',
        ),
      );

      expect(orderId, 91);
      final request = adapter.requests.single;
      expect(request.path, '/api/checkout');
      expect(request.data['selections'], [
        {'cartItemId': 41, 'quantity': 1},
        {'cartItemId': 42, 'quantity': 2},
      ]);
      expect(request.data['address']['recipient'], 'Buyer');
      expect((request.data as Map).containsKey('price'), isFalse);
    });

    test('Course-only checkout still refuses a cart that has a Product', () async {
      final cart = CartSnapshot(
        revision: 1,
        items: [
          CartItem(
            id: 2,
            itemId: 9,
            type: CartItemType.product,
            name: 'Brush set',
            unitPriceVnd: 50000,
          ),
        ],
        subtotalVnd: 50000,
        canCheckout: true,
        hasUnavailableItems: false,
      );

      await expectLater(
        ApiCourseCheckoutRepository(apiClient: api)
            .createOrder(cart: cart, idempotencyKey: 'product-key'),
        throwsFormatException,
      );
      expect(adapter.requests, isEmpty);
    });
  });
}

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
