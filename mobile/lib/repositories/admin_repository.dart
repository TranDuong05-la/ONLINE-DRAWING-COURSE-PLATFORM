import '../core/constants/api_constants.dart';
import '../core/network/api_client.dart';
import '../models/page_response.dart';
import '../models/user_model.dart';

class AdminRepository {
  final ApiClient _apiClient;

  AdminRepository({ApiClient? apiClient})
      : _apiClient = apiClient ?? ApiClient();

  Future<PageResponse<UserModel>> getAllUsers({
    String? search,
    String? role,
    bool? isActive,
    int page = 0,
    int size = 20,
  }) async {
    final query = <String, dynamic>{
      'page': page,
      'size': size,
    };
    if (search != null && search.trim().isNotEmpty) {
      query['search'] = search.trim();
    }
    if (role != null && role.isNotEmpty && role != 'ALL') {
      query['role'] = role;
    }
    if (isActive != null) {
      query['isActive'] = isActive;
    }

    final response = await _apiClient.get<PageResponse<UserModel>>(
      ApiConstants.adminUsers,
      queryParameters: query,
      fromJsonT: (json) => PageResponse<UserModel>.fromJson(
        json as Map<String, dynamic>,
        (item) => UserModel.fromJson(item as Map<String, dynamic>),
      ),
    );

    if (response.data != null) {
      return response.data!;
    }
    return PageResponse<UserModel>(
      content: [],
      totalElements: 0,
      totalPages: 0,
      size: size,
      number: page,
      first: true,
      last: true,
      empty: true,
    );
  }

  Future<UserModel> getUserById(int id) async {
    final response = await _apiClient.get<UserModel>(
      ApiConstants.adminUserDetail(id),
      fromJsonT: (json) => UserModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Không tìm thấy người dùng');
  }

  Future<UserModel> updateUserStatus(int id, bool isActive) async {
    final response = await _apiClient.patch<UserModel>(
      ApiConstants.adminUserStatus(id),
      data: {'isActive': isActive},
      fromJsonT: (json) => UserModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Cập nhật trạng thái người dùng thất bại');
  }
}
