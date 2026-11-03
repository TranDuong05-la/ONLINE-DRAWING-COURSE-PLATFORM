enum UserRole {
  user,
  instructor,
  staff,
  admin;

  static UserRole fromString(String? role) {
    if (role == null) return UserRole.user;
    final normalized = role.toUpperCase().replaceAll('ROLE_', '').trim();
    switch (normalized) {
      case 'ADMIN':
        return UserRole.admin;
      case 'INSTRUCTOR':
        return UserRole.instructor;
      case 'STAFF':
        return UserRole.staff;
      case 'USER':
      default:
        return UserRole.user;
    }
  }

  String toServerString() {
    switch (this) {
      case UserRole.admin:
        return 'ADMIN';
      case UserRole.instructor:
        return 'INSTRUCTOR';
      case UserRole.staff:
        return 'STAFF';
      case UserRole.user:
        return 'USER';
    }
  }

  String get displayName {
    switch (this) {
      case UserRole.admin:
        return 'Quản trị viên (Admin)';
      case UserRole.instructor:
        return 'Giảng viên (Instructor)';
      case UserRole.staff:
        return 'Nhân viên (Staff)';
      case UserRole.user:
        return 'Học viên (User)';
    }
  }
}

class UserModel {
  final int id;
  final String fullName;
  final String email;
  final String? phone;
  final UserRole role;
  final String? avatarUrl;
  final bool isActive;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  UserModel({
    required this.id,
    required this.fullName,
    required this.email,
    this.phone,
    required this.role,
    this.avatarUrl,
    this.isActive = true,
    this.createdAt,
    this.updatedAt,
  });

  bool get isAdmin => role == UserRole.admin;
  bool get isInstructor => role == UserRole.instructor;
  bool get isStaff => role == UserRole.staff;
  bool get isUser => role == UserRole.user;

  factory UserModel.fromJson(Map<String, dynamic> json) {
    return UserModel(
      id: json['id'] as int? ?? 0,
      fullName: json['fullName'] as String? ?? '',
      email: json['email'] as String? ?? '',
      phone: json['phone'] as String?,
      role: UserRole.fromString(json['role'] as String?),
      avatarUrl: json['avatarUrl'] as String?,
      isActive: json['isActive'] as bool? ?? true,
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'] as String)
          : null,
      updatedAt: json['updatedAt'] != null
          ? DateTime.tryParse(json['updatedAt'] as String)
          : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'fullName': fullName,
      'email': email,
      'phone': phone,
      'role': role.toServerString(),
      'avatarUrl': avatarUrl,
      'isActive': isActive,
      'createdAt': createdAt?.toIso8601String(),
      'updatedAt': updatedAt?.toIso8601String(),
    };
  }

  UserModel copyWith({
    int? id,
    String? fullName,
    String? email,
    String? phone,
    UserRole? role,
    String? avatarUrl,
    bool? isActive,
    DateTime? createdAt,
    DateTime? updatedAt,
  }) {
    return UserModel(
      id: id ?? this.id,
      fullName: fullName ?? this.fullName,
      email: email ?? this.email,
      phone: phone ?? this.phone,
      role: role ?? this.role,
      avatarUrl: avatarUrl ?? this.avatarUrl,
      isActive: isActive ?? this.isActive,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }
}
