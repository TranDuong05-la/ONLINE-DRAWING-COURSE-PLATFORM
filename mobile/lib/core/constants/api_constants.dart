import 'package:flutter/foundation.dart';

class ApiConstants {
  // Automatically detect platform for default Base URL:
  // - Web (Chrome/Firefox/Edge) or Desktop: http://localhost:8080
  // - Android Emulator: http://10.0.2.2:8080
  static String get defaultBaseUrl {
    if (kIsWeb) {
      return 'http://localhost:8080';
    }
    if (defaultTargetPlatform == TargetPlatform.android) {
      return 'http://10.0.2.2:8080';
    }
    return 'http://localhost:8080';
  }

  // Storage key for user-configured base URL
  static const String baseUrlKey = 'artnest_api_base_url';

  // Auth endpoints
  static const String register = '/api/auth/register';
  static const String login = '/api/auth/login';
  static const String refresh = '/api/auth/refresh';
  static const String logout = '/api/auth/logout';
  static const String authMe = '/api/auth/me';
  static const String forgotPassword = '/api/auth/forgot-password';
  static const String resetPassword = '/api/auth/reset-password';
  static const String googleLogin = '/api/auth/google';

  // User profile endpoints
  static const String userProfile = '/api/users/me';
  static const String changePassword = '/api/users/me/password';

  // Address endpoints
  static const String addresses = '/api/users/me/addresses';
  static String addressDetail(int id) => '/api/users/me/addresses/$id';
  static String addressSetDefault(int id) => '/api/users/me/addresses/$id/default';

  // Admin endpoints
  static const String adminUsers = '/api/admin/users';
  static String adminUserDetail(int id) => '/api/admin/users/$id';
  static String adminUserStatus(int id) => '/api/admin/users/$id/status';
}
