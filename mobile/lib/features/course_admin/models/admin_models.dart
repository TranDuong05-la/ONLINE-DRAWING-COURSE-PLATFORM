import '../../course/models/course_models.dart';

int _int(dynamic v) => (v as num?)?.toInt() ?? 0;

/// Bài học ở màn admin: có đủ mọi trường, kể cả link video/tài liệu và cờ ẩn.
class AdminLesson {
  AdminLesson({
    required this.id,
    required this.moduleId,
    required this.title,
    this.description,
    this.videoUrl,
    required this.durationSeconds,
    this.materialUrl,
    required this.preview,
    required this.required,
    required this.hidden,
  });

  final int id;
  final int moduleId;
  final String title;
  final String? description;
  final String? videoUrl;
  final int durationSeconds;
  final String? materialUrl;
  final bool preview;
  final bool required;
  final bool hidden;

  factory AdminLesson.fromJson(Map<String, dynamic> json) {
    return AdminLesson(
      id: _int(json['id']),
      moduleId: _int(json['moduleId']),
      title: json['title'] as String? ?? '',
      description: json['description'] as String?,
      videoUrl: json['videoUrl'] as String?,
      durationSeconds: _int(json['durationSeconds']),
      materialUrl: json['materialUrl'] as String?,
      preview: json['preview'] as bool? ?? false,
      required: json['required'] as bool? ?? false,
      hidden: json['hidden'] as bool? ?? false,
    );
  }
}

class AdminModule {
  AdminModule({
    required this.id,
    required this.courseId,
    required this.title,
    required this.hidden,
    required this.lessons,
  });

  final int id;
  final int courseId;
  final String title;
  final bool hidden;
  final List<AdminLesson> lessons;

  factory AdminModule.fromJson(Map<String, dynamic> json) {
    return AdminModule(
      id: _int(json['id']),
      courseId: _int(json['courseId']),
      title: json['title'] as String? ?? '',
      hidden: json['hidden'] as bool? ?? false,
      lessons: (json['lessons'] as List? ?? [])
          .map((e) => AdminLesson.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}

/// Chi tiết khóa ở màn admin. publishProblems = các điều kiện còn thiếu để xuất bản.
class AdminCourseDetail {
  AdminCourseDetail({
    required this.course,
    this.description,
    required this.modules,
    required this.kits,
    required this.publishProblems,
  });

  final CourseSummary course;
  final String? description;
  final List<AdminModule> modules;
  final List<Kit> kits;
  final List<String> publishProblems;

  factory AdminCourseDetail.fromJson(Map<String, dynamic> json) {
    return AdminCourseDetail(
      course: CourseSummary.fromJson(json['course'] as Map<String, dynamic>),
      description: json['description'] as String?,
      modules: (json['modules'] as List? ?? [])
          .map((e) => AdminModule.fromJson(e as Map<String, dynamic>))
          .toList(),
      kits: (json['kits'] as List? ?? [])
          .map((e) => Kit.fromJson(e as Map<String, dynamic>))
          .toList(),
      publishProblems: (json['publishProblems'] as List? ?? []).map((e) => '$e').toList(),
    );
  }
}

/// Sản phẩm để chọn làm quà tặng.
class Product {
  Product({
    required this.id,
    required this.sku,
    required this.name,
    required this.price,
    this.thumbnailUrl,
  });

  final int id;
  final String sku;
  final String name;
  final int price;
  final String? thumbnailUrl;

  factory Product.fromJson(Map<String, dynamic> json) {
    return Product(
      id: _int(json['id']),
      sku: json['sku'] as String? ?? '',
      name: json['name'] as String? ?? '',
      price: _int(json['price']),
      thumbnailUrl: json['thumbnailUrl'] as String?,
    );
  }
}
