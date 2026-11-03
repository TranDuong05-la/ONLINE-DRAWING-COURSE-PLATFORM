import 'dart:convert';
import 'dart:typed_data';

import 'package:artnest_app/core/constants/api_constants.dart';
import 'package:artnest_app/core/network/api_client.dart';
import 'package:artnest_app/core/storage/token_storage.dart';
import 'package:artnest_app/models/user_model.dart';
import 'package:artnest_app/repositories/admin_repository.dart';
import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';

class _RecordingAdapter implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    final data = options.path.endsWith('/role-history')
        ? [
            {
              'id': 8,
              'userId': 42,
              'userEmail': 'user@example.test',
              'userFullName': 'Example User',
              'changedById': 7,
              'changedByEmail': 'admin@example.test',
              'changedByFullName': 'Example Admin',
              'oldRole': 'USER',
              'newRole': 'INSTRUCTOR',
              'reason': 'Approved instructor',
              'changedAt': '2026-11-03T10:15:30',
            },
          ]
        : {
            'id': 42,
            'fullName': 'Example User',
            'email': 'user@example.test',
            'role': 'INSTRUCTOR',
            'isActive': true,
          };
    return ResponseBody.fromString(
      jsonEncode({'success': true, 'message': 'ok', 'data': data}),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ApiClient client;
  late _RecordingAdapter adapter;
  late AdminRepository repository;

  setUp(() async {
    FlutterSecureStorage.setMockInitialValues({});
    client = ApiClient(tokenStorage: TokenStorage());
    client.onSessionExpired = null;
    adapter = _RecordingAdapter();
    client.dio.httpClientAdapter = adapter;
    repository = AdminRepository(apiClient: client);
  });

  test(
    'role assignment uses A1 PUT contract and server-derived actor',
    () async {
      final user = await repository.updateUserRole(
        42,
        role: 'INSTRUCTOR',
        reason: '  Approved instructor  ',
      );

      expect(user.role, UserRole.instructor);
      final request = adapter.requests.single;
      expect(request.method, 'PUT');
      expect(request.path, ApiConstants.adminUserRole(42));
      final body = request.data is String
          ? jsonDecode(request.data as String) as Map<String, dynamic>
          : request.data as Map<String, dynamic>;
      expect(body, {'role': 'INSTRUCTOR', 'reason': 'Approved instructor'});
      expect(body.containsKey('actorUserId'), isFalse);
    },
  );

  test('role history parses backend fields and all supported roles', () async {
    final history = await repository.getUserRoleHistory(42);

    expect(adapter.requests.single.method, 'GET');
    expect(adapter.requests.single.path, ApiConstants.adminUserRoleHistory(42));
    expect(history, hasLength(1));
    expect(history.single.oldRole, UserRole.user);
    expect(history.single.newRole, UserRole.instructor);
    expect(history.single.changedByFullName, 'Example Admin');
    expect(history.single.changedAt, DateTime(2026, 11, 3, 10, 15, 30));
  });

  test('status management preserves the existing PATCH contract', () async {
    await repository.updateUserStatus(42, false);
    final request = adapter.requests.single;
    expect(request.method, 'PATCH');
    expect(request.path, ApiConstants.adminUserStatus(42));
    final body = request.data is String
        ? jsonDecode(request.data as String) as Map<String, dynamic>
        : request.data as Map<String, dynamic>;
    expect(body, {'isActive': false});
  });

  test('user model retains STAFF and INSTRUCTOR roles', () {
    expect(UserRole.fromString('STAFF'), UserRole.staff);
    expect(UserRole.fromString('INSTRUCTOR').toServerString(), 'INSTRUCTOR');
  });
}
