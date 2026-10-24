class UserProfileModel {
  final int id;
  final int userId;
  final String profileType; // 'LEARNER' | 'INSTRUCTOR'
  final String displayName;
  final String? avatarUrl;
  final String ageGroup; // 'KIDS' | 'TEEN' | 'ADULT' | 'SENIOR'
  final String? bio;
  final String? signatureImageUrl;
  final bool isApproved;
  final DateTime? createdAt;

  UserProfileModel({
    required this.id,
    required this.userId,
    required this.profileType,
    required this.displayName,
    this.avatarUrl,
    this.ageGroup = 'ADULT',
    this.bio,
    this.signatureImageUrl,
    this.isApproved = false,
    this.createdAt,
  });

  factory UserProfileModel.fromJson(Map<String, dynamic> json) {
    return UserProfileModel(
      id: json['id'] as int? ?? 0,
      userId: json['userId'] as int? ?? 0,
      profileType: json['profileType'] as String? ?? 'LEARNER',
      displayName: json['displayName'] as String? ?? '',
      avatarUrl: json['avatarUrl'] as String?,
      ageGroup: json['ageGroup'] as String? ?? 'ADULT',
      bio: json['bio'] as String?,
      signatureImageUrl: json['signatureImageUrl'] as String?,
      isApproved: json['isApproved'] as bool? ?? false,
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'] as String)
          : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'userId': userId,
      'profileType': profileType,
      'displayName': displayName,
      'avatarUrl': avatarUrl,
      'ageGroup': ageGroup,
      'bio': bio,
      'signatureImageUrl': signatureImageUrl,
      'isApproved': isApproved,
      'createdAt': createdAt?.toIso8601String(),
    };
  }
}
