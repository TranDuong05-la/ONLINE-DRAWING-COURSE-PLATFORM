import 'package:artnest_app/core/router/app_router.dart';
import 'package:artnest_app/core/network/api_exceptions.dart';
import 'package:artnest_app/features/order_history/data/order_history_repository.dart';
import 'package:artnest_app/features/order_history/models/order_history_models.dart';
import 'package:artnest_app/features/order_history/order_detail_screen.dart';
import 'package:artnest_app/features/order_history/order_history_screen.dart';
import 'package:artnest_app/providers/auth_provider.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'order_history_test_data.dart';

void main() {
  testWidgets(
    'authenticated list navigates to persisted Order detail without checkout actions',
    (tester) async {
      final auth = _TestAuthProvider(authenticated: true);
      final repository = _FakeRepository(
        list: () async => parsedPage([52]),
        detail: (id) async => OrderDetail.fromJson(orderDetailJson(id)),
      );
      final router = AppRouter.createRouter(
        auth,
        orderHistoryRepository: repository,
      );
      router.go('/orders');
      await tester.pumpWidget(_app(router, auth));
      await tester.pumpAndSettle();
      expect(find.byType(OrderHistoryScreen), findsOneWidget);
      expect(find.text('Order #52'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('order-52')));
      await tester.pumpAndSettle();
      expect(find.byType(OrderDetailScreen), findsOneWidget);
      expect(find.text('Historical brush set'), findsOneWidget);
      expect(find.text('Checkout'), findsNothing);
      expect(find.text('VNPay'), findsNothing);
      router.dispose();
      auth.dispose();
    },
  );

  testWidgets('unauthenticated Order History route redirects to login', (
    tester,
  ) async {
    final auth = _TestAuthProvider(authenticated: false);
    final router = AppRouter.createRouter(
      auth,
      orderHistoryRepository: _FakeRepository(
        list: () async => parsedPage([]),
        detail: (id) async => throw NotFoundException(),
      ),
    );
    router.go('/orders');
    await tester.pumpWidget(_app(router, auth));
    await tester.pumpAndSettle();
    expect(router.routeInformationProvider.value.uri.path, '/login');
    router.dispose();
    auth.dispose();
  });

  testWidgets(
    'refresh failure syncs through AuthProvider logout and router redirect',
    (tester) async {
      final auth = _TestAuthProvider(authenticated: true);
      final router = AppRouter.createRouter(
        auth,
        orderHistoryRepository: _FakeRepository(
          list: () async => throw UnauthorizedException(),
          detail: (id) async => throw UnauthorizedException(),
        ),
      );
      router.go('/orders');
      await tester.pumpWidget(_app(router, auth));
      await tester.pumpAndSettle();
      expect(auth.logoutCalls, 1);
      expect(auth.isAuthenticated, isFalse);
      expect(router.routeInformationProvider.value.uri.path, '/login');
      router.dispose();
      auth.dispose();
    },
  );

  testWidgets(
    'valid empty history stays empty and network errors do not log out',
    (tester) async {
      final emptyAuth = _TestAuthProvider(authenticated: true);
      final emptyRouter = AppRouter.createRouter(
        emptyAuth,
        orderHistoryRepository: _FakeRepository(
          list: () async => parsedPage([]),
          detail: (id) async => throw NotFoundException(),
        ),
      );
      emptyRouter.go('/orders');
      await tester.pumpWidget(_app(emptyRouter, emptyAuth));
      await tester.pumpAndSettle();
      expect(find.text('No orders yet'), findsOneWidget);
      expect(find.textContaining('sample orders'), findsOneWidget);
      expect(find.textContaining('Order #'), findsNothing);
      emptyRouter.dispose();
      emptyAuth.dispose();

      final networkAuth = _TestAuthProvider(authenticated: true);
      final networkRouter = AppRouter.createRouter(
        networkAuth,
        orderHistoryRepository: _FakeRepository(
          list: () async => throw NetworkException(),
          detail: (id) async => throw NotFoundException(),
        ),
      );
      networkRouter.go('/orders');
      await tester.pumpWidget(_app(networkRouter, networkAuth));
      await tester.pumpAndSettle();
      expect(networkAuth.logoutCalls, 0);
      expect(networkAuth.isAuthenticated, isTrue);
      expect(
        find.text(
          'Could not load Order History. Check your connection and retry.',
        ),
        findsOneWidget,
      );
      networkRouter.dispose();
      networkAuth.dispose();
    },
  );

  testWidgets(
    '404 detail renders unavailable state and does not reveal foreign data',
    (tester) async {
      final auth = _TestAuthProvider(authenticated: true);
      final router = AppRouter.createRouter(
        auth,
        orderHistoryRepository: _FakeRepository(
          list: () async => parsedPage([]),
          detail: (id) async =>
              throw NotFoundException(message: 'Order not found'),
        ),
      );
      router.go('/orders/999');
      await tester.pumpWidget(_app(router, auth));
      await tester.pumpAndSettle();
      expect(find.byType(OrderDetailScreen), findsOneWidget);
      expect(find.text('This Order is not available.'), findsOneWidget);
      router.dispose();
      auth.dispose();
    },
  );
}

class _TestAuthProvider extends AuthProvider {
  _TestAuthProvider({required this.authenticated});
  bool authenticated;
  int logoutCalls = 0;
  @override
  bool get isAuthenticated => authenticated;
  @override
  Future<void> logout() async {
    logoutCalls++;
    authenticated = false;
    notifyListeners();
  }
}

class _FakeRepository implements OrderHistoryRepository {
  _FakeRepository({required this.list, required this.detail});
  final Future<PaginatedOrderHistory> Function() list;
  final Future<OrderDetail> Function(int id) detail;
  @override
  Future<PaginatedOrderHistory> getOrders({int page = 0, int size = 20}) =>
      list();
  @override
  Future<OrderDetail> getOrderDetail(int orderId) => detail(orderId);
}

Widget _app(GoRouter router, AuthProvider auth) => MaterialApp.router(
  routerConfig: router,
  builder: (context, child) =>
      ChangeNotifierProvider.value(value: auth, child: child!),
);
