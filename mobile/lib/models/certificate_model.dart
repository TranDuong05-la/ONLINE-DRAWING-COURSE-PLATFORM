class CertificateModel {
  final int id;
  final String code;
  final int courseId;
  final String courseTitle;
  final DateTime? issuedAt;

  /// PENDING, SENT hoặc FAILED
  final String emailStatus;
  final int emailAttempts;
  final DateTime? emailSentAt;
  final String? emailError;
  final bool canResendEmail;

  CertificateModel({
    required this.id,
    required this.code,
    required this.courseId,
    required this.courseTitle,
    this.issuedAt,
    required this.emailStatus,
    required this.emailAttempts,
    this.emailSentAt,
    this.emailError,
    required this.canResendEmail,
  });

  factory CertificateModel.fromJson(Map<String, dynamic> json) {
    return CertificateModel(
      id: (json['id'] as num).toInt(),
      code: json['code'] as String? ?? '',
      courseId: (json['courseId'] as num?)?.toInt() ?? 0,
      courseTitle: json['courseTitle'] as String? ?? '',
      issuedAt: json['issuedAt'] != null ? DateTime.tryParse(json['issuedAt'] as String) : null,
      emailStatus: json['emailStatus'] as String? ?? 'PENDING',
      emailAttempts: (json['emailAttempts'] as num?)?.toInt() ?? 0,
      emailSentAt: json['emailSentAt'] != null ? DateTime.tryParse(json['emailSentAt'] as String) : null,
      emailError: json['emailError'] as String?,
      canResendEmail: json['canResendEmail'] as bool? ?? false,
    );
  }
}
