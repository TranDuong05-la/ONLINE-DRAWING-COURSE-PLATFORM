import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:artnest_app/core/constants/api_constants.dart';
import 'package:artnest_app/core/network/api_client.dart';
import 'package:artnest_app/core/network/api_exceptions.dart';
import 'package:artnest_app/core/storage/token_storage.dart';

/// Records requests and answers with a fixed status code.
class _FakeAdapter implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int statusCode = 200;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    return ResponseBody.fromString(
      jsonEncode({'success': statusCode < 400, 'message': 'ok'}),
      statusCode,
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

  test('Android default base URL targets the emulator host, not localhost', () {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    addTearDown(() => debugDefaultTargetPlatformOverride = null);
    expect(ApiConstants.defaultBaseUrl, 'http://10.0.2.2:8080');
  });

  group('ApiClient auth headers', () {
    late _FakeAdapter adapter;
    late TokenStorage storage;
    late ApiClient client;

    setUp(() async {
      FlutterSecureStorage.setMockInitialValues({});
      storage = TokenStorage();
      await storage.saveTokens(accessToken: 'test-access', refreshToken: 'test-refresh');
      client = ApiClient(tokenStorage: storage);
      adapter = _FakeAdapter();
      client.dio.httpClientAdapter = adapter;
    });

    test('public auth endpoints are sent without a Bearer token', () async {
      for (final path in [
        ApiConstants.login,
        ApiConstants.googleLogin,
        ApiConstants.forgotPassword,
        ApiConstants.resetPassword,
      ]) {
        await client.post(path, data: {});
        expect(adapter.requests.last.headers['Authorization'], isNull,
            reason: path);
      }
    });

    test('protected endpoints carry the stored Bearer token', () async {
      await client.get(ApiConstants.authMe);
      expect(adapter.requests.last.headers['Authorization'], 'Bearer test-access');
    });

    test('401 from Google login does not trigger refresh or wipe the session', () async {
      adapter.statusCode = 401;
      await expectLater(
        client.post(ApiConstants.googleLogin, data: {}),
        throwsA(isA<UnauthorizedException>()),
      );
      expect(adapter.requests, hasLength(1));
      expect(await storage.getRefreshToken(), 'test-refresh');
    });
  });
}
