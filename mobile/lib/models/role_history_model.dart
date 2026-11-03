import 'user_model.dart';

class RoleHistoryModel {
  final int id;
  final int userId;
  final String userEmail;
  final String userFullName;
  final int changedById;
  final String changedByEmail;
  final String changedByFullName;
  final UserRole oldRole;
  final UserRole newRole;
  final String? reason;
  final DateTime changedAt;

  RoleHistoryModel({
    required this.id,
    required this.userId,
    required this.userEmail,
    required this.userFullName,
    required this.changedById,
    required this.changedByEmail,
    required this.changedByFullName,
    required this.oldRole,
    required this.newRole,
    this.reason,
    required this.changedAt,
  });

  factory RoleHistoryModel.fromJson(Map<String, dynamic> json) {
    return RoleHistoryModel(
      id: (json['id'] as num?)?.toInt() ?? 0,
      userId: (json['userId'] as num?)?.toInt() ?? 0,
      userEmail: json['userEmail'] as String? ?? '',
      userFullName: json['userFullName'] as String? ?? '',
      changedById: (json['changedById'] as num?)?.toInt() ?? 0,
      changedByEmail: json['changedByEmail'] as String? ?? '',
      changedByFullName: json['changedByFullName'] as String? ?? '',
      oldRole: UserRole.fromString(json['oldRole'] as String?),
      newRole: UserRole.fromString(json['newRole'] as String?),
      reason: json['reason'] as String?,
      changedAt: json['changedAt'] != null
          ? DateTime.parse(json['changedAt'] as String)
          : DateTime.now(),
    );
  }
}
