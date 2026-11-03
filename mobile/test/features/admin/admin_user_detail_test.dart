import 'package:artnest_app/features/admin/admin_user_detail_screen.dart';
import 'package:artnest_app/models/admin_role_history.dart';
import 'package:artnest_app/models/user_model.dart';
import 'package:artnest_app/repositories/admin_repository.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';

class _FakeAdminRepository extends AdminRepository {
  UserModel user = UserModel(
    id: 42,
    fullName: 'Example User',
    email: 'user@example.test',
    role: UserRole.user,
  );
  String? submittedRole;
  int historyReads = 0;

  @override
  Future<UserModel> getUserById(int id) async => user;

  @override
  Future<UserModel> updateUserRole(
    int id, {
    required String role,
    String? reason,
  }) async {
    submittedRole = role;
    user = user.copyWith(role: UserRole.fromString(role));
    return user;
  }

  @override
  Future<List<AdminRoleHistory>> getUserRoleHistory(int id) async {
    historyReads++;
    return [
      AdminRoleHistory(
        id: 8,
        userId: id,
        userEmail: user.email,
        userFullName: user.fullName,
        changedById: 7,
        changedByEmail: 'admin@example.test',
        changedByFullName: 'Example Admin',
        oldRole: UserRole.user,
        newRole: UserRole.instructor,
        reason: 'Approved instructor',
        changedAt: DateTime(2026, 11, 3, 10, 15),
      ),
    ];
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'admin detail submits role change and displays role audit history',
    (tester) async {
      GoogleFonts.config.allowRuntimeFetching = false;
      final repository = _FakeAdminRepository();
      await tester.pumpWidget(
        MaterialApp(
          home: AdminUserDetailScreen(
            userId: 42,
            initialUser: repository.user,
            repository: repository,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Role history'), findsOneWidget);
      expect(find.textContaining('Approved instructor'), findsOneWidget);
      expect(find.text('USER → INSTRUCTOR'), findsOneWidget);

      await tester.ensureVisible(find.text('Change role'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Change role'));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(DropdownButtonFormField<UserRole>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('INSTRUCTOR').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Save role'));
      await tester.pumpAndSettle();

      expect(repository.submittedRole, 'INSTRUCTOR');
      expect(repository.historyReads, 2);
      expect(find.text('INSTRUCTOR'), findsWidgets);
    },
  );
}
