import '../../course/models/course_models.dart';

int _int(dynamic v) => (v as num?)?.toInt() ?? 0;

/// Combo khóa học (API public). savings = originalTotal - price.
class Bundle {
  Bundle({
    required this.id,
    required this.code,
    required this.title,
    this.description,
    required this.price,
    required this.originalTotal,
    required this.savings,
    required this.courses,
  });

  final int id;
  final String code;
  final String title;
  final String? description;
  final int price;
  final int originalTotal;
  final int savings;
  final List<CourseSummary> courses;

  factory Bundle.fromJson(Map<String, dynamic> json) {
    return Bundle(
      id: _int(json['id']),
      code: json['code'] as String? ?? '',
      title: json['title'] as String? ?? '',
      description: json['description'] as String?,
      price: _int(json['price']),
      originalTotal: _int(json['originalTotal']),
      savings: _int(json['savings']),
      courses: (json['courses'] as List? ?? [])
          .map((e) => CourseSummary.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}

/// Combo ở màn admin: thêm trạng thái bật/tắt, cảnh báo.
class AdminBundle {
  AdminBundle({
    required this.id,
    required this.code,
    required this.title,
    this.description,
    required this.price,
    required this.active,
    required this.originalTotal,
    required this.savings,
    required this.visibleToPublic,
    required this.warnings,
    required this.courses,
  });

  final int id;
  final String code;
  final String title;
  final String? description;
  final int price;
  final bool active;
  final int originalTotal;
  final int savings;
  final bool visibleToPublic;
  final List<String> warnings;
  final List<CourseSummary> courses;

  factory AdminBundle.fromJson(Map<String, dynamic> json) {
    return AdminBundle(
      id: _int(json['id']),
      code: json['code'] as String? ?? '',
      title: json['title'] as String? ?? '',
      description: json['description'] as String?,
      price: _int(json['price']),
      active: json['active'] as bool? ?? false,
      originalTotal: _int(json['originalTotal']),
      savings: _int(json['savings']),
      visibleToPublic: json['visibleToPublic'] as bool? ?? false,
      warnings: (json['warnings'] as List? ?? []).map((e) => '$e').toList(),
      courses: (json['courses'] as List? ?? [])
          .map((e) => CourseSummary.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}
