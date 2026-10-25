import 'package:artnest_app/features/cart/cart_item.dart';
import 'package:artnest_app/features/cart/cart_repository.dart';
import 'package:artnest_app/features/cart/cart_screen.dart';
import 'package:artnest_app/features/cart/mock_cart_repository.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Integer VND pricing and mock quantity rules', () async {
    final repository = MockCartRepository();
    final initial = await repository.load();
    expect(initial.fold<int>(0, (sum, item) => sum + item.subtotalVnd), 785000);
    expect(formatVnd(1234567), '1.234.567 ₫');
    expect(formatVnd(0), '0 ₫');
    expect(() => initial.clear(), throwsUnsupportedError);
    await expectLater(repository.setQuantity(1, 2), throwsArgumentError);
    await expectLater(repository.setQuantity(2, 0), throwsArgumentError);
    expect((await repository.setQuantity(2, 3))[1].subtotalVnd, 375000);
    expect((await repository.load()).first.quantity, 1);
    expect((await repository.remove(1)).length, 2);
    expect(await repository.clear(), isEmpty);
    expect(await repository.load(), isEmpty);
    expect(await MockCartRepository(initialItems: []).load(), isEmpty);
  });

  Future<void> showCart(
    WidgetTester tester, {
    CartRepository? repository,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        ),
        home: CartScreen(repository: repository ?? MockCartRepository()),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('Demo data is clearly labeled', (tester) async {
    await showCart(tester);
    expect(find.text('Demo cart · Sample data'), findsOneWidget);
  });
  testWidgets('Product controls recalculate totals and stop at one', (
    tester,
  ) async {
    await showCart(tester);
    expect(find.text('COURSE'), findsOneWidget);
    expect(find.text('PRODUCT'), findsNWidgets(2));
    expect(find.byKey(const ValueKey('increase-1')), findsNothing);
    expect(find.text('Quantity: 1 · Individual access'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('increase-2')));
    await tester.pumpAndSettle();
    expect(
      tester.widget<Text>(find.byKey(const ValueKey('quantity-2'))).data,
      '3',
    );
    expect(
      tester.widget<Text>(find.byKey(const ValueKey('cart-total'))).data,
      '910.000 ₫',
    );
    for (var i = 0; i < 2; i++) {
      await tester.tap(find.byKey(const ValueKey('decrease-2')));
      await tester.pumpAndSettle();
    }
    expect(
      tester
          .widget<IconButton>(find.byKey(const ValueKey('decrease-2')))
          .onPressed,
      isNull,
    );
    expect(
      tester.widget<Text>(find.byKey(const ValueKey('cart-total'))).data,
      '660.000 ₫',
    );
  });

  testWidgets('Remove one item, cancel clear, then confirm clear', (
    tester,
  ) async {
    await showCart(tester);
    await tester.tap(find.byTooltip('Remove Watercolor foundations'));
    await tester.pumpAndSettle();
    expect(find.text('Watercolor foundations'), findsNothing);
    expect(
      tester.widget<Text>(find.byKey(const ValueKey('cart-total'))).data,
      '335.000 ₫',
    );
    await tester.tap(find.text('Clear all'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Keep items'));
    await tester.pumpAndSettle();
    expect(find.text('Watercolor brush set'), findsOneWidget);
    await tester.tap(find.text('Clear all'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Clear cart'));
    await tester.pumpAndSettle();
    expect(find.text('Your cart is empty'), findsOneWidget);
    expect(find.text('Checkout'), findsNothing);
  });

  testWidgets('Empty repository shows empty state without checkout', (
    tester,
  ) async {
    await showCart(tester, repository: MockCartRepository(initialItems: []));
    expect(find.text('Your cart is empty'), findsOneWidget);
    expect(find.text('Checkout'), findsNothing);
    expect(
      tester
          .widget<TextButton>(find.widgetWithText(TextButton, 'Clear all'))
          .onPressed,
      isNull,
    );
  });

  testWidgets('Checkout explains integration without changing the cart', (
    tester,
  ) async {
    await showCart(tester);
    await tester.ensureVisible(find.text('Checkout'));
    await tester.tap(find.text('Checkout'));
    await tester.pumpAndSettle();
    expect(find.text('Checkout is not available yet'), findsOneWidget);
    await tester.tap(find.text('Back to cart'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('item-1')), findsOneWidget);
    expect(
      tester.widget<Text>(find.byKey(const ValueKey('cart-total'))).data,
      '785.000 ₫',
    );
  });

  testWidgets('Repository error can be reloaded', (tester) async {
    await showCart(tester, repository: _FailOnceRepository());
    expect(
      find.text('Could not update your cart. Please try again.'),
      findsOneWidget,
    );
    expect(find.text('Your cart is empty'), findsNothing);
    await tester.tap(find.text('Reload cart'));
    await tester.pumpAndSettle();
    expect(find.text('Watercolor foundations'), findsOneWidget);
  });

  for (final width in [320.0, 1000.0]) {
    testWidgets('Layout fits width $width with enlarged text', (tester) async {
      tester.view.physicalSize = Size(width, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        MaterialApp(
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(1.5)),
            child: child!,
          ),
          home: CartScreen(repository: MockCartRepository()),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Checkout'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }
}

class _FailOnceRepository extends MockCartRepository {
  bool _first = true;
  @override
  Future<List<CartItem>> load() async {
    if (_first) {
      _first = false;
      throw StateError('Offline');
    }
    return super.load();
  }
}
