import '../core/constants/api_constants.dart';
import '../core/network/api_client.dart';
import '../models/address_model.dart';

class AddressRepository {
  final ApiClient _apiClient;

  AddressRepository({ApiClient? apiClient})
      : _apiClient = apiClient ?? ApiClient();

  Future<List<AddressModel>> getAddresses() async {
    final response = await _apiClient.get<List<AddressModel>>(
      ApiConstants.addresses,
      fromJsonT: (json) {
        if (json is List) {
          return json
              .map((item) => AddressModel.fromJson(item as Map<String, dynamic>))
              .toList();
        }
        return <AddressModel>[];
      },
    );

    return response.data ?? [];
  }

  Future<AddressModel> createAddress(AddressRequest request) async {
    final response = await _apiClient.post<AddressModel>(
      ApiConstants.addresses,
      data: request.toJson(),
      fromJsonT: (json) => AddressModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Tạo địa chỉ thất bại');
  }

  Future<AddressModel> updateAddress(int id, AddressRequest request) async {
    final response = await _apiClient.put<AddressModel>(
      ApiConstants.addressDetail(id),
      data: request.toJson(),
      fromJsonT: (json) => AddressModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Cập nhật địa chỉ thất bại');
  }

  Future<void> deleteAddress(int id) async {
    final response = await _apiClient.delete<void>(
      ApiConstants.addressDetail(id),
    );

    if (!response.success && response.message.isNotEmpty) {
      throw Exception(response.message);
    }
  }

  Future<AddressModel> setDefaultAddress(int id) async {
    final response = await _apiClient.patch<AddressModel>(
      ApiConstants.addressSetDefault(id),
      fromJsonT: (json) => AddressModel.fromJson(json as Map<String, dynamic>),
    );

    if (response.data != null) {
      return response.data!;
    }
    throw Exception(response.message.isNotEmpty ? response.message : 'Cập nhật địa chỉ mặc định thất bại');
  }
}
