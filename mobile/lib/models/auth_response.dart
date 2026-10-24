import 'user_model.dart';

class AuthResponseModel {
  final String accessToken;
  final String tokenType;
  final int expiresIn;
  final String refreshToken;
  final UserModel user;

  AuthResponseModel({
    required this.accessToken,
    this.tokenType = 'Bearer',
    required this.expiresIn,
    required this.refreshToken,
    required this.user,
  });

  factory AuthResponseModel.fromJson(Map<String, dynamic> json) {
    return AuthResponseModel(
      accessToken: json['accessToken'] as String? ?? '',
      tokenType: json['tokenType'] as String? ?? 'Bearer',
      expiresIn: (json['expiresIn'] as num?)?.toInt() ?? 0,
      refreshToken: json['refreshToken'] as String? ?? '',
      user: json['user'] != null
          ? UserModel.fromJson(json['user'] as Map<String, dynamic>)
          : UserModel(
              id: 0,
              fullName: '',
              email: '',
              role: UserRole.user,
            ),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'accessToken': accessToken,
      'tokenType': tokenType,
      'expiresIn': expiresIn,
      'refreshToken': refreshToken,
      'user': user.toJson(),
    };
  }
}
