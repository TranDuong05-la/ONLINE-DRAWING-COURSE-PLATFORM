import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../utils/app_logger.dart';

class TokenStorage {
  static const String _keyAccessToken = 'artnest_access_token';
  static const String _keyRefreshToken = 'artnest_refresh_token';
  static const String _keyBaseUrl = 'artnest_base_url';

  final FlutterSecureStorage _storage;

  TokenStorage({FlutterSecureStorage? storage})
      : _storage = storage ??
            const FlutterSecureStorage(
              aOptions: AndroidOptions(
                encryptedSharedPreferences: true,
              ),
            );

  Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    try {
      await _storage.write(key: _keyAccessToken, value: accessToken);
      await _storage.write(key: _keyRefreshToken, value: refreshToken);
    } catch (e) {
      logDebug('TokenStorage saveTokens error: $e');
    }
  }

  Future<void> saveAccessToken(String accessToken) async {
    try {
      await _storage.write(key: _keyAccessToken, value: accessToken);
    } catch (e) {
      logDebug('TokenStorage saveAccessToken error: $e');
    }
  }

  Future<String?> getAccessToken() async {
    try {
      return await _storage.read(key: _keyAccessToken);
    } catch (e) {
      logDebug('TokenStorage getAccessToken error: $e');
      // If keystore corruption happens, clear all to reset clean state
      try {
        await _storage.deleteAll();
      } catch (_) {}
      return null;
    }
  }

  Future<String?> getRefreshToken() async {
    try {
      return await _storage.read(key: _keyRefreshToken);
    } catch (e) {
      logDebug('TokenStorage getRefreshToken error: $e');
      return null;
    }
  }

  Future<void> clearTokens() async {
    try {
      await _storage.delete(key: _keyAccessToken);
      await _storage.delete(key: _keyRefreshToken);
    } catch (e) {
      logDebug('TokenStorage clearTokens error: $e');
    }
  }

  Future<bool> hasAccessToken() async {
    final token = await getAccessToken();
    return token != null && token.isNotEmpty;
  }

  Future<void> saveBaseUrl(String url) async {
    try {
      await _storage.write(key: _keyBaseUrl, value: url);
    } catch (e) {
      logDebug('TokenStorage saveBaseUrl error: $e');
    }
  }

  Future<String?> getBaseUrl() async {
    try {
      return await _storage.read(key: _keyBaseUrl);
    } catch (e) {
      logDebug('TokenStorage getBaseUrl error: $e');
      return null;
    }
  }

  Future<void> clearAll() async {
    try {
      await _storage.deleteAll();
    } catch (e) {
      logDebug('TokenStorage clearAll error: $e');
    }
  }
}
