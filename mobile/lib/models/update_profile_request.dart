class UpdateProfileRequest {
  final String? fullName;
  final String? phone;
  final String? avatarUrl;

  UpdateProfileRequest({
    this.fullName,
    this.phone,
    this.avatarUrl,
  });

  Map<String, dynamic> toJson() {
    final map = <String, dynamic>{};
    if (fullName != null) map['fullName'] = fullName!.trim();
    if (phone != null) map['phone'] = phone!.trim();
    if (avatarUrl != null) map['avatarUrl'] = avatarUrl;
    return map;
  }
}
