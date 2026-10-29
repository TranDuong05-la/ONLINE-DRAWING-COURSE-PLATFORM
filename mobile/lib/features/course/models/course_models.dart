// Model dữ liệu khóa học phía học viên. Tên trường khớp JSON của backend.

int _int(dynamic v) => (v as num?)?.toInt() ?? 0;

/// Kết quả phân trang: { content, page, size, totalElements, totalPages }.
class PageResult<T> {
  PageResult({
    required this.items,
    required this.page,
    required this.totalPages,
    required this.totalElements,
  });

  final List<T> items;
  final int page;
  final int totalPages;
  final int totalElements;

  bool get hasMore => page + 1 < totalPages;

  factory PageResult.fromJson(Map<String, dynamic> json, T Function(Map<String, dynamic>) parse) {
    return PageResult(
      items: (json['content'] as List).map((e) => parse(e as Map<String, dynamic>)).toList(),
      page: _int(json['page']),
      totalPages: _int(json['totalPages']),
      totalElements: _int(json['totalElements']),
    );
  }
}

/// Thông tin thẻ khóa học. effectivePrice = giá giảm nếu có, không thì giá gốc.
class CourseSummary {
  CourseSummary({
    required this.id,
    required this.code,
    required this.title,
    this.shortDescription,
    this.thumbnailUrl,
    required this.price,
    this.discountPrice,
    required this.effectivePrice,
    required this.level,
    required this.status,
    required this.hasKits,
    required this.lessonCount,
    required this.totalDurationSeconds,
  });

  final int id;
  final String code;
  final String title;
  final String? shortDescription;
  final String? thumbnailUrl;
  final int price;
  final int? discountPrice;
  final int effectivePrice;
  final String level; // BASIC | ADVANCED
  final String status; // DRAFT | PUBLISHED | HIDDEN
  final bool hasKits;
  final int lessonCount;
  final int totalDurationSeconds;

  bool get hasDiscount => discountPrice != null && discountPrice! < price;

  factory CourseSummary.fromJson(Map<String, dynamic> json) {
    return CourseSummary(
      id: _int(json['id']),
      code: json['code'] as String? ?? '',
      title: json['title'] as String? ?? '',
      shortDescription: json['shortDescription'] as String?,
      thumbnailUrl: json['thumbnailUrl'] as String?,
      price: _int(json['price']),
      discountPrice: (json['discountPrice'] as num?)?.toInt(),
      effectivePrice: _int(json['effectivePrice']),
      level: json['level'] as String? ?? 'BASIC',
      status: json['status'] as String? ?? 'DRAFT',
      hasKits: json['hasKits'] as bool? ?? false,
      lessonCount: _int(json['lessonCount']),
      totalDurationSeconds: _int(json['totalDurationSeconds']),
    );
  }
}

/// Quà tặng kèm.
class Kit {
  Kit({
    required this.productId,
    required this.sku,
    required this.name,
    this.thumbnailUrl,
    required this.quantity,
  });

  final int productId;
  final String sku;
  final String name;
  final String? thumbnailUrl;
  final int quantity;

  factory Kit.fromJson(Map<String, dynamic> json) {
    return Kit(
      productId: _int(json['productId']),
      sku: json['sku'] as String? ?? '',
      name: json['name'] as String? ?? '',
      thumbnailUrl: json['thumbnailUrl'] as String?,
      quantity: _int(json['quantity']),
    );
  }
}

/// Bài học ở API public: KHÔNG có link video (trừ bài học thử).
class PublicLesson {
  PublicLesson({
    required this.id,
    required this.title,
    this.description,
    required this.durationSeconds,
    required this.preview,
    required this.required,
    required this.hasDocument,
    this.previewVideoUrl,
  });

  final int id;
  final String title;
  final String? description;
  final int durationSeconds;
  final bool preview;
  final bool required;
  final bool hasDocument;
  final String? previewVideoUrl;

  factory PublicLesson.fromJson(Map<String, dynamic> json) {
    return PublicLesson(
      id: _int(json['id']),
      title: json['title'] as String? ?? '',
      description: json['description'] as String?,
      durationSeconds: _int(json['durationSeconds']),
      preview: json['preview'] as bool? ?? false,
      required: json['required'] as bool? ?? false,
      hasDocument: json['hasDocument'] as bool? ?? false,
      previewVideoUrl: json['previewVideoUrl'] as String?,
    );
  }
}

class PublicModule {
  PublicModule({required this.id, required this.title, required this.lessons});

  final int id;
  final String title;
  final List<PublicLesson> lessons;

  factory PublicModule.fromJson(Map<String, dynamic> json) {
    return PublicModule(
      id: _int(json['id']),
      title: json['title'] as String? ?? '',
      lessons: (json['lessons'] as List? ?? [])
          .map((e) => PublicLesson.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}

class CourseDetail {
  CourseDetail({
    required this.course,
    this.description,
    required this.modules,
    required this.kits,
  });

  final CourseSummary course;
  final String? description;
  final List<PublicModule> modules;
  final List<Kit> kits;

  factory CourseDetail.fromJson(Map<String, dynamic> json) {
    return CourseDetail(
      course: CourseSummary.fromJson(json['course'] as Map<String, dynamic>),
      description: json['description'] as String?,
      modules: (json['modules'] as List? ?? [])
          .map((e) => PublicModule.fromJson(e as Map<String, dynamic>))
          .toList(),
      kits: (json['kits'] as List? ?? [])
          .map((e) => Kit.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}
