import 'package:dio/dio.dart';
import 'package:image_picker/image_picker.dart';
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

  Future<UserModel> uploadAvatar(XFile file) async {
    final bytes = await file.readAsBytes();
    final fileName = file.name.isNotEmpty ? file.name : 'avatar.jpg';
    final formData = FormData.fromMap({
      'file': MultipartFile.fromBytes(
        bytes,
        filename: fileName,
      ),
    });

    final response = await _apiClient.post<UserModel>(
      ApiConstants.userAvatar,
      data: formData,
      fromJsonT: (json) => UserModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Tải lên ảnh đại diện thất bại');
  }

  Future<String> uploadImage(XFile file) async {
    final bytes = await file.readAsBytes();
    final fileName = file.name.isNotEmpty ? file.name : 'image.jpg';
    final formData = FormData.fromMap({
      'file': MultipartFile.fromBytes(
        bytes,
        filename: fileName,
      ),
    });

    final response = await _apiClient.post<Map<String, dynamic>>(
      ApiConstants.uploadImage,
      data: formData,
      fromJsonT: (json) => json as Map<String, dynamic>,
    );

    if (response.data != null && response.data!['url'] != null) {
      return response.data!['url'] as String;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Tải ảnh lên thất bại');
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
