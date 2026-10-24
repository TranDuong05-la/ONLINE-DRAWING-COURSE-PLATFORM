import '../core/constants/api_constants.dart';
import '../core/network/api_client.dart';
import '../core/storage/token_storage.dart';
import '../models/change_password_request.dart';
import '../models/update_profile_request.dart';
import '../models/user_model.dart';

class UserRepository {
  final ApiClient _apiClient;
  final TokenStorage _tokenStorage;

  UserRepository({
    ApiClient? apiClient,
    TokenStorage? tokenStorage,
  })  : _apiClient = apiClient ?? ApiClient(),
        _tokenStorage = tokenStorage ?? TokenStorage();

  Future<UserModel> getProfile() async {
    final response = await _apiClient.get<UserModel>(
      ApiConstants.userProfile,
      fromJsonT: (json) => UserModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Không thể lấy thông tin hồ sơ');
  }

  Future<UserModel> updateProfile(UpdateProfileRequest request) async {
    final response = await _apiClient.put<UserModel>(
      ApiConstants.userProfile,
      data: request.toJson(),
      fromJsonT: (json) => UserModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Cập nhật hồ sơ thất bại');
  }

  Future<void> changePassword(ChangePasswordRequest request) async {
    final response = await _apiClient.put<void>(
      ApiConstants.changePassword,
      data: request.toJson(),
    );

    if (!response.success && response.message.isNotEmpty) {
      throw Exception(response.message);
    }

    // Backend revokes all refresh tokens on password change
    await _tokenStorage.clearTokens();
  }
}
