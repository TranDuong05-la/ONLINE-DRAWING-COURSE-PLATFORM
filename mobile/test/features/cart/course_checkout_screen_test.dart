import 'package:artnest_app/features/cart/cart_item.dart';
import 'package:artnest_app/features/cart/course_checkout_repository.dart';
import 'package:artnest_app/features/cart/course_checkout_screen.dart';
import 'package:artnest_app/features/order_history/models/order_history_models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets(
    'checkout uses persisted status and never treats redirect as payment success',
    (tester) async {
      final repository = _CheckoutFake();
      Uri? opened;
      await tester.pumpWidget(
        MaterialApp(
          home: CourseCheckoutScreen(
            cart: _cart(),
            repository: repository,
            purchaseEnabled: true,
            launchPaymentUrl: (uri) async {
              opened = uri;
              return true;
            },
          ),
        ),
      );

      await tester.tap(find.byKey(const ValueKey('place-course-order')));
      await tester.pumpAndSettle();
      expect(repository.createdOrder, 81);
      expect(repository.createdVoucher, isNull);
      expect(repository.idempotencyKey, startsWith('mobile-'));
      expect(opened?.host, 'sandbox.vnpayment.vn');
      expect(find.textContaining('does not confirm payment'), findsOneWidget);
      expect(
        find.textContaining('Payment is confirmed by the server'),
        findsNothing,
      );
      expect(
        find.textContaining('Server-calculated discount: 25.000'),
        findsOneWidget,
      );

      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('check-payment-status')),
        160,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.byKey(const ValueKey('check-payment-status')));
      await tester.pumpAndSettle();
      expect(repository.ownershipRefreshes, 1);
      expect(
        find.textContaining('Payment is confirmed by the server'),
        findsOneWidget,
      );
    },
  );

  testWidgets('checkout stays disabled by default unless explicitly enabled', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: CourseCheckoutScreen(
          cart: _cart(),
          repository: _CheckoutFake(),
          purchaseEnabled: false,
        ),
      ),
    );
    expect(
      find.text('Course purchasing is disabled in this app build.'),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('place-course-order')), findsNothing);
  });

  testWidgets('exclusive expired payment deadline prevents opening VNPay', (
    tester,
  ) async {
    final repository = _CheckoutFake(expired: true);
    await tester.pumpWidget(
      MaterialApp(
        home: CourseCheckoutScreen(
          cart: _cart(),
          repository: repository,
          purchaseEnabled: true,
          launchPaymentUrl: (_) async => true,
        ),
      ),
    );
    await tester.tap(find.byKey(const ValueKey('place-course-order')));
    await tester.pumpAndSettle();
    expect(find.textContaining('payment deadline has passed'), findsOneWidget);
    expect(repository.paymentSessionRequests, 0);
  });
}

CartSnapshot _cart() => CartSnapshot(
  cartId: 5,
  revision: 9,
  items: [
    CartItem(
      id: 22,
      itemId: 102,
      type: CartItemType.course,
      name: 'Painting',
      unitPriceVnd: 200000,
    ),
  ],
  subtotalVnd: 200000,
  canCheckout: true,
  hasUnavailableItems: false,
);

class _CheckoutFake implements CourseCheckoutRepository {
  _CheckoutFake({this.expired = false});

  final bool expired;
  int? createdOrder;
  String? idempotencyKey;
  String? createdVoucher;
  int orderReads = 0;
  int ownershipRefreshes = 0;
  int paymentSessionRequests = 0;

  @override
  Future<int> createOrder({
    required CartSnapshot cart,
    required String idempotencyKey,
    String? voucherCode,
  }) async {
    this.idempotencyKey = idempotencyKey;
    createdVoucher = voucherCode;
    createdOrder = 81;
    return 81;
  }

  @override
  Future<CoursePaymentSession> createPaymentSession(int orderId) async {
    paymentSessionRequests++;
    return CoursePaymentSession(
      orderId: orderId,
      paymentUrl: Uri.parse(
        'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?signature=test',
      ),
      expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 20)),
      reused: false,
    );
  }

  @override
  Future<OrderDetail> getOrder(int orderId) async {
    orderReads++;
    final settled = orderReads > 1;
    return OrderDetail(
      orderId: orderId,
      status: settled ? OrderStatus.confirmed : OrderStatus.pendingPayment,
      paymentStatus: settled
          ? OrderPaymentStatus.succeeded
          : OrderPaymentStatus.pending,
      currency: 'VND',
      createdAt: DateTime.utc(2026, 11, 3),
      updatedAt: DateTime.utc(2026, 11, 3),
      paymentExpiresAt: expired
          ? DateTime.now().toUtc().subtract(const Duration(seconds: 1))
          : DateTime.now().toUtc().add(const Duration(minutes: 20)),
      merchandiseSubtotalVnd: 200000,
      eligibleSubtotalVnd: 200000,
      discountVnd: 25000,
      shippingFeeVnd: 0,
      totalVnd: 175000,
      voucher: const OrderVoucherSnapshot(
        code: 'ART25',
        kind: OrderVoucherKind.fixedVnd,
        value: 25000,
        maximumDiscountVnd: 25000,
      ),
      items: const [
        OrderItem(
          orderItemId: 90,
          linePosition: 0,
          itemType: OrderItemType.course,
          itemId: 102,
          nameSnapshot: 'Painting',
          quantity: 1,
          unitPriceVnd: 200000,
          voucherEligible: true,
          grossAmountVnd: 200000,
          allocatedDiscountVnd: 25000,
          netAmountVnd: 175000,
        ),
      ],
      shippingAddress: null,
    );
  }

  @override
  Future<Set<int>> refreshOwnedCourses() async {
    ownershipRefreshes++;
    return {102};
  }
}
