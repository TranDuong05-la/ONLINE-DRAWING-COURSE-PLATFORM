import 'package:flutter_test/flutter_test.dart';
import 'package:artnest_app/models/api_response.dart';
import 'package:artnest_app/models/user_model.dart';
import 'package:artnest_app/models/address_model.dart';
import 'package:artnest_app/models/auth_response.dart';

void main() {
  group('ArtNest DTO & Model Tests', () {
    test('ApiResponse parses success JSON correctly', () {
      final json = {
        'success': true,
        'message': 'Login successful',
        'data': {
          'id': 1,
          'fullName': 'Nguyen Van A',
          'email': 'user@artnest.vn',
          'role': 'USER',
          'isActive': true,
        },
      };

      final response = ApiResponse<UserModel>.fromJson(
        json,
        (data) => UserModel.fromJson(data as Map<String, dynamic>),
      );

      expect(response.success, true);
      expect(response.message, 'Login successful');
      expect(response.data?.fullName, 'Nguyen Van A');
      expect(response.data?.role, UserRole.user);
      expect(response.data?.isAdmin, false);
    });

    test('AuthResponseModel parses token and user correctly', () {
      final json = {
        'accessToken': 'jwt_access_token_123',
        'tokenType': 'Bearer',
        'expiresIn': 900,
        'refreshToken': 'refresh_token_xyz',
        'user': {
          'id': 2,
          'fullName': 'Admin ArtNest',
          'email': 'admin@artnest.vn',
          'role': 'ADMIN',
          'isActive': true,
        },
      };

      final auth = AuthResponseModel.fromJson(json);

      expect(auth.accessToken, 'jwt_access_token_123');
      expect(auth.tokenType, 'Bearer');
      expect(auth.expiresIn, 900);
      expect(auth.refreshToken, 'refresh_token_xyz');
      expect(auth.user.isAdmin, true);
      expect(auth.user.role, UserRole.admin);
    });

    test('AddressModel parses correctly and formats fullAddress', () {
      final json = {
        'id': 10,
        'receiverName': 'Tran Thi B',
        'phone': '0987654321',
        'province': 'TP. Hà Nội',
        'district': 'Quận Cầu Giấy',
        'ward': 'Phường Dịch Vọng',
        'streetAddress': 'Số 10 Duy Tân',
        'isDefault': true,
      };

      final address = AddressModel.fromJson(json);

      expect(address.id, 10);
      expect(address.receiverName, 'Tran Thi B');
      expect(address.isDefault, true);
      expect(
        address.fullAddress,
        'Số 10 Duy Tân, Phường Dịch Vọng, Quận Cầu Giấy, TP. Hà Nội',
      );
    });
  });
}
