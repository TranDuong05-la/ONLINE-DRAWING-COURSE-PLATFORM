import 'package:artnest_app/features/cart/cart_item.dart';
import 'package:artnest_app/features/cart/cart_repository.dart';
import 'package:artnest_app/features/cart/cart_screen.dart';
import 'package:artnest_app/features/cart/mock_cart_repository.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'Cart snapshot retains integer prices and Course quantity is one',
    () async {
      final repository = MockCartRepository(
        initialItems: [
          CartItem(
            id: 1,
            itemId: 101,
            type: CartItemType.course,
            name: 'Course',
            unitPriceVnd: 450000,
          ),
        ],
      );
      final initial = await repository.load();
      expect(initial.subtotalVnd, 450000);
      expect(initial.items.single.quantity, 1);
      await expectLater(repository.setQuantity(1, 2), throwsArgumentError);
      await repository.remove(1);
      expect((await repository.load()).items, isEmpty);
    },
  );

  Future<void> showCart(
    WidgetTester tester, {
    CartRepository? repository,
    bool enabled = true,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        ),
        home: CartScreen(
          repository: repository ?? MockCartRepository(),
          purchaseEnabled: enabled,
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('purchase UI is disabled when the app opt-in is false', (
    tester,
  ) async {
    final repository = _CountingRepository();
    await showCart(tester, repository: repository, enabled: false);
    expect(
      find.textContaining('Course purchasing is disabled'),
      findsOneWidget,
    );
    expect(repository.loads, 0);
  });

  testWidgets(
    'Course prices are displayed and product content blocks Course-only checkout',
    (tester) async {
      await showCart(tester);
      expect(find.text('Watercolor foundations'), findsOneWidget);
      expect(find.text('Course items'), findsOneWidget);
      expect(
        find.textContaining('Product purchases are not supported'),
        findsNWidgets(2),
      );
      expect(
        find.byKey(const ValueKey('start-course-checkout')),
        findsOneWidget,
      );
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey('start-course-checkout')),
            )
            .onPressed,
        isNull,
      );
    },
  );

  testWidgets(
    'remove and clear operate on persisted-cart repository snapshots',
    (tester) async {
      await showCart(
        tester,
        repository: MockCartRepository(
          initialItems: [
            CartItem(
              id: 1,
              itemId: 101,
              type: CartItemType.course,
              name: 'Painting',
              unitPriceVnd: 100000,
            ),
          ],
        ),
      );
      await tester.tap(find.byTooltip('Remove Painting'));
      await tester.pumpAndSettle();
      expect(find.text('Your cart is empty'), findsOneWidget);
      expect(find.textContaining('Voucher'), findsNothing);
    },
  );

  testWidgets(
    'repository errors can be retried without presenting fake cart data',
    (tester) async {
      await showCart(tester, repository: _FailOnceRepository());
      expect(find.byKey(const ValueKey('cart-error')), findsOneWidget);
      expect(find.text('Watercolor foundations'), findsNothing);
      await tester.tap(find.text('Retry'));
      await tester.pumpAndSettle();
      expect(find.text('Watercolor foundations'), findsOneWidget);
    },
  );
}

class _CountingRepository extends MockCartRepository {
  int loads = 0;
  @override
  Future<CartSnapshot> load() async {
    loads++;
    return super.load();
  }
}

class _FailOnceRepository extends MockCartRepository {
  bool _first = true;
  @override
  Future<CartSnapshot> load() async {
    if (_first) {
      _first = false;
      throw StateError('Offline');
    }
    return super.load();
  }
}
