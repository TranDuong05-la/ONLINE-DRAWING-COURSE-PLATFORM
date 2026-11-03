import 'package:artnest_app/features/admin/admin_dashboard_screen.dart';
import 'package:artnest_app/core/router/app_router.dart';
import 'package:artnest_app/models/auth_response.dart';
import 'package:artnest_app/models/user_model.dart';
import 'package:artnest_app/providers/auth_provider.dart';
import 'package:artnest_app/repositories/auth_repository.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';

void main() {
  testWidgets('dashboard does not present fabricated metrics', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: AdminDashboardScreen()));

    expect(find.text('Dashboard metrics unavailable'), findsOneWidget);
    expect(
      find.textContaining('does not currently provide an admin dashboard'),
      findsOneWidget,
    );
    final disabledMetrics = tester.widget<ListTile>(
      find.ancestor(
        of: find.text('Dashboard metrics unavailable'),
        matching: find.byType(ListTile),
      ),
    );
    expect(disabledMetrics.enabled, isFalse);
  });

  testWidgets('invalidated session exits the Admin route to login', (
    tester,
  ) async {
    final auth = AuthProvider(authRepository: _AdminAuthRepository());
    await auth.login(email: 'admin@example.test', password: 'unused');
    final router = AppRouter.createRouter(auth);
    router.go('/admin/dashboard');
    await tester.pumpWidget(
      ChangeNotifierProvider.value(
        value: auth,
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(AdminDashboardScreen), findsOneWidget);

    auth.handleSessionExpired();
    await tester.pumpAndSettle();
    expect(router.routeInformationProvider.value.uri.path, '/login');
    router.dispose();
    auth.dispose();
  });
}

class _AdminAuthRepository extends AuthRepository {
  @override
  Future<AuthResponseModel> login({
    required String email,
    required String password,
  }) async => AuthResponseModel(
    accessToken: 'access',
    expiresIn: 60,
    refreshToken: 'refresh',
    user: UserModel(
      id: 1,
      fullName: 'Admin',
      email: email,
      role: UserRole.admin,
    ),
  );
}
