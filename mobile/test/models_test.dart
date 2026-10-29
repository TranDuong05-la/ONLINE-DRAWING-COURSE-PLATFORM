import 'package:artnest_app/features/course_admin/models/admin_models.dart';
import 'package:artnest_app/features/bundle/models/bundle_models.dart';
import 'package:artnest_app/features/course/models/course_models.dart';
import 'package:flutter_test/flutter_test.dart';

/// JSON mẫu đúng định dạng backend trả về.
Map<String, dynamic> courseJson({int? discountPrice, bool hasKits = false}) => {
      'id': 7,
      'code': 'KH-1',
      'slug': 'khoa-1',
      'title': 'Vẽ màu nước',
      'shortDescription': 'Mô tả ngắn',
      'thumbnailUrl': null,
      'price': 600000,
      'discountPrice': discountPrice,
      'effectivePrice': discountPrice ?? 600000,
      'level': 'ADVANCED',
      'status': 'PUBLISHED',
      'hasKits': hasKits,
      'lessonCount': 3,
      'totalDurationSeconds': 120,
      'publishedAt': '2026-10-21T10:00:00',
      'createdAt': '2026-10-21T10:00:00',
      'updatedAt': '2026-10-21T10:00:00',
    };

void main() {
  test('CourseSummary đọc đúng các trường, kể cả giá giảm null', () {
    final noDiscount = CourseSummary.fromJson(courseJson());
    expect(noDiscount.id, 7);
    expect(noDiscount.level, 'ADVANCED');
    expect(noDiscount.discountPrice, isNull);
    expect(noDiscount.hasDiscount, isFalse);
    expect(noDiscount.effectivePrice, 600000);

    final discounted = CourseSummary.fromJson(courseJson(discountPrice: 449000, hasKits: true));
    expect(discounted.hasDiscount, isTrue);
    expect(discounted.effectivePrice, 449000);
    expect(discounted.hasKits, isTrue);
  });

  test('CourseDetail: bài thường không có previewVideoUrl, bài học thử có', () {
    final detail = CourseDetail.fromJson({
      'course': courseJson(),
      'description': 'Mô tả chi tiết',
      'modules': [
        {
          'id': 1,
          'title': 'Chương 1',
          'sortOrder': 1,
          'lessons': [
            {
              'id': 10,
              'title': 'Bài học thử',
              'description': null,
              'durationSeconds': 7,
              'preview': true,
              'required': false,
              'hasDocument': false,
              'previewVideoUrl': 'https://x/preview.mp4',
            },
            {
              'id': 11,
              'title': 'Bài trả phí',
              'description': 'Mô tả',
              'durationSeconds': 8,
              'preview': false,
              'required': true,
              'hasDocument': true,
              'previewVideoUrl': null,
            },
          ],
        },
      ],
      'kits': [
        {'productId': 1, 'sku': 'BM-WC12', 'name': 'Bộ màu nước', 'thumbnailUrl': null, 'quantity': 2},
      ],
    });

    expect(detail.modules.single.lessons, hasLength(2));
    final preview = detail.modules.single.lessons[0];
    final paid = detail.modules.single.lessons[1];
    expect(preview.preview, isTrue);
    expect(preview.previewVideoUrl, 'https://x/preview.mp4');
    expect(paid.previewVideoUrl, isNull);
    expect(paid.required, isTrue);
    expect(paid.hasDocument, isTrue);
    expect(detail.kits.single.quantity, 2);
    expect(detail.kits.single.name, 'Bộ màu nước');
  });

  test('PageResult: hasMore dựa trên số trang', () {
    final first = PageResult<CourseSummary>.fromJson(
      {
        'content': [courseJson()],
        'page': 0,
        'size': 10,
        'totalElements': 15,
        'totalPages': 2,
      },
      CourseSummary.fromJson,
    );
    expect(first.items, hasLength(1));
    expect(first.hasMore, isTrue);

    final last = PageResult<CourseSummary>.fromJson(
      {'content': [], 'page': 1, 'size': 10, 'totalElements': 15, 'totalPages': 2},
      CourseSummary.fromJson,
    );
    expect(last.hasMore, isFalse);
  });

  test('Bundle và AdminBundle', () {
    final bundle = Bundle.fromJson({
      'id': 3,
      'code': 'CB-1',
      'title': 'Combo',
      'description': null,
      'price': 799000,
      'originalTotal': 899000,
      'savings': 100000,
      'courses': [courseJson(), courseJson(discountPrice: 449000)],
    });
    expect(bundle.courses, hasLength(2));
    expect(bundle.savings, 100000);

    final admin = AdminBundle.fromJson({
      'id': 3,
      'code': 'CB-1',
      'title': 'Combo',
      'price': 799000,
      'active': true,
      'originalTotal': 899000,
      'savings': 100000,
      'visibleToPublic': false,
      'warnings': ['Khóa X đang HIDDEN'],
      'courses': [courseJson()],
    });
    expect(admin.visibleToPublic, isFalse);
    expect(admin.warnings.single, 'Khóa X đang HIDDEN');
  });

  test('AdminCourseDetail: module, bài ẩn và điều kiện xuất bản còn thiếu', () {
    final detail = AdminCourseDetail.fromJson({
      'course': courseJson(),
      'description': null,
      'modules': [
        {
          'id': 1,
          'courseId': 7,
          'title': 'Chương 1',
          'sortOrder': 1,
          'hidden': false,
          'lessons': [
            {
              'id': 10,
              'moduleId': 1,
              'title': 'Bài 1',
              'description': null,
              'videoUrl': null,
              'durationSeconds': 0,
              'materialUrl': null,
              'sortOrder': 1,
              'preview': false,
              'required': false,
              'hidden': true,
            },
          ],
        },
      ],
      'kits': [],
      'publishProblems': ['cần ít nhất 1 bài học đang hiển thị'],
    });
    expect(detail.modules.single.lessons.single.hidden, isTrue);
    expect(detail.modules.single.lessons.single.videoUrl, isNull);
    expect(detail.publishProblems, hasLength(1));
  });
}
