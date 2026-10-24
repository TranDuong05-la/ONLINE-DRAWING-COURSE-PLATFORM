import '../core/constants/api_constants.dart';
import '../core/network/api_client.dart';
import '../core/storage/token_storage.dart';
import '../models/auth_response.dart';
import '../models/user_model.dart';

class AuthRepository {
  final ApiClient _apiClient;
  final TokenStorage _tokenStorage;

  AuthRepository({
    ApiClient? apiClient,
    TokenStorage? tokenStorage,
  })  : _apiClient = apiClient ?? ApiClient(),
        _tokenStorage = tokenStorage ?? TokenStorage();

  Future<AuthResponseModel> register({
    required String fullName,
    required String email,
    required String password,
    String? phone,
  }) async {
    final payload = <String, dynamic>{
      'fullName': fullName.trim(),
      'email': email.trim().toLowerCase(),
      'password': password,
    };
    if (phone != null && phone.trim().isNotEmpty) {
      payload['phone'] = phone.trim();
    }

    final response = await _apiClient.post<AuthResponseModel>(
      ApiConstants.register,
      data: payload,
      fromJsonT: (json) => AuthResponseModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      await _tokenStorage.saveTokens(
        accessToken: response.data!.accessToken,
        refreshToken: response.data!.refreshToken,
      );
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Đăng ký không thành công');
  }

  Future<AuthResponseModel> login({
    required String email,
    required String password,
  }) async {
    final response = await _apiClient.post<AuthResponseModel>(
      ApiConstants.login,
      data: {
        'email': email.trim().toLowerCase(),
        'password': password,
      },
      fromJsonT: (json) => AuthResponseModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      await _tokenStorage.saveTokens(
        accessToken: response.data!.accessToken,
        refreshToken: response.data!.refreshToken,
      );
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Đăng nhập không thành công');
  }

  Future<AuthResponseModel> refreshToken(String refreshToken) async {
    final response = await _apiClient.post<AuthResponseModel>(
      ApiConstants.refresh,
      data: {'refreshToken': refreshToken},
      fromJsonT: (json) => AuthResponseModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      await _tokenStorage.saveTokens(
        accessToken: response.data!.accessToken,
        refreshToken: response.data!.refreshToken,
      );
      return response.data!;
    }
    throw Exception('Làm mới token thất bại');
  }

  Future<void> logout() async {
    final refreshToken = await _tokenStorage.getRefreshToken();
    if (refreshToken != null && refreshToken.isNotEmpty) {
      try {
        await _apiClient.post<void>(
          ApiConstants.logout,
          data: {'refreshToken': refreshToken},
        );
      } catch (_) {
        // Even if server call fails, proceed to clean local tokens
      }
    }
    await _tokenStorage.clearTokens();
  }

  Future<UserModel> getCurrentUser() async {
    final response = await _apiClient.get<UserModel>(
      ApiConstants.authMe,
      fromJsonT: (json) => UserModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Không thể lấy thông tin người dùng');
  }

  Future<bool> isAuthenticated() async {
    return await _tokenStorage.hasAccessToken();
  }

  Future<void> sendForgotPasswordOtp(String email) async {
    await _apiClient.post<void>(
      ApiConstants.forgotPassword,
      data: {'email': email.trim().toLowerCase()},
    );
  }

  Future<void> resetPassword({
    required String email,
    required String otp,
    required String newPassword,
    required String confirmPassword,
  }) async {
    await _apiClient.post<void>(
      ApiConstants.resetPassword,
      data: {
        'email': email.trim().toLowerCase(),
        'otp': otp.trim(),
        'newPassword': newPassword,
        'confirmPassword': confirmPassword,
      },
    );
  }

  Future<AuthResponseModel> loginWithGoogle({
    required String email,
    String? fullName,
    String? avatarUrl,
    String? idToken,
  }) async {
    final response = await _apiClient.post<AuthResponseModel>(
      ApiConstants.googleLogin,
      data: {
        'email': email.trim().toLowerCase(),
        'fullName': fullName,
        'avatarUrl': avatarUrl,
        'idToken': idToken,
      },
      fromJsonT: (json) => AuthResponseModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      await _tokenStorage.saveTokens(
        accessToken: response.data!.accessToken,
        refreshToken: response.data!.refreshToken,
      );
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Đăng nhập Google thất bại');
  }
}
