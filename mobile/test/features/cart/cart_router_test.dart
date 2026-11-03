import 'package:artnest_app/core/router/app_router.dart';
import 'package:artnest_app/features/cart/cart_screen.dart';
import 'package:artnest_app/features/home/store_tab.dart';
import 'package:artnest_app/providers/auth_provider.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

void main() {
  testWidgets(
    'authenticated Cart route uses default-off Course purchase gate',
    (tester) async {
      final auth = _AuthenticatedAuthProvider();
      final router = AppRouter.createRouter(auth);
      router.go('/cart');
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: auth,
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(CartScreen), findsOneWidget);
      expect(
        find.textContaining('Course purchasing is disabled'),
        findsOneWidget,
      );
      router.dispose();
      auth.dispose();
    },
  );

  testWidgets('unauthenticated Cart route redirects to login', (tester) async {
    final auth = AuthProvider();
    final router = AppRouter.createRouter(auth);
    router.go('/cart');
    await tester.pumpWidget(
      ChangeNotifierProvider.value(
        value: auth,
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();
    expect(router.routeInformationProvider.value.uri.path, '/login');
    router.dispose();
    auth.dispose();
  });

  testWidgets('Store exposes a Course Cart entry', (tester) async {
    final router = GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(path: '/', builder: (context, state) => const StoreTab()),
        GoRoute(
          path: '/cart',
          builder: (context, state) => const Scaffold(body: Text('Cart route')),
        ),
      ],
    );
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    expect(find.byKey(const ValueKey('open-course-cart')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('open-course-cart')));
    await tester.pumpAndSettle();
    expect(find.text('Cart route'), findsOneWidget);
    router.dispose();
  });
}

class _AuthenticatedAuthProvider extends AuthProvider {
  @override
  bool get isAuthenticated => true;
}
