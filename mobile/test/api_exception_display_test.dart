import 'package:artnest_app/core/network/api_exceptions.dart';
import 'package:artnest_app/models/api_response.dart';
import 'package:artnest_app/features/course/models/course_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('ApiExceptionDisplay (hiển thị lỗi của phần khóa học)', () {
    test('lỗi nhập liệu: lấy thông báo của ô sai đầu tiên thay vì "Validation failed"', () {
      final e = ValidationException(
        message: 'Validation failed',
        errors: [
          ApiFieldError(field: 'title', message: 'Tên khóa học không được để trống'),
          ApiFieldError(field: 'price', message: 'Giá gốc là bắt buộc'),
        ],
      );
      expect(e.displayMessage, 'Tên khóa học không được để trống');
      expect(e.fieldErrorMap, {
        'title': 'Tên khóa học không được để trống',
        'price': 'Giá gốc là bắt buộc',
      });
    });

    test('lỗi nghiệp vụ không có danh sách ô: dùng message của server', () {
      final e = ApiException(message: 'Giá giảm phải nhỏ hơn giá gốc', statusCode: 400);
      expect(e.displayMessage, 'Giá giảm phải nhỏ hơn giá gốc');
      expect(e.fieldErrorMap, isEmpty);
    });
  });

  group('ApiResponse bọc dữ liệu khóa học', () {
    test('bóc phần data của danh sách phân trang giống backend đã ghép', () {
      final json = {
        'success': true,
        'message': 'OK',
        'data': {
          'content': [
            {
              'id': 1,
              'code': 'KH-1',
              'slug': 'khoa-1',
              'title': 'Vẽ màu nước',
              'price': 450000,
              'effectivePrice': 450000,
              'level': 'BASIC',
              'status': 'PUBLISHED',
              'hasKits': true,
              'lessonCount': 3,
              'totalDurationSeconds': 120,
            },
          ],
          'page': 0,
          'size': 10,
          'totalElements': 1,
          'totalPages': 1,
        },
        'timestamp': '2026-10-27T10:00:00',
      };
      final response = ApiResponse<dynamic>.fromJson(json, (j) => j);
      final page = PageResult<CourseSummary>.fromJson(
        response.data as Map<String, dynamic>,
        CourseSummary.fromJson,
      );
      expect(page.items.single.title, 'Vẽ màu nước');
      expect(page.items.single.hasKits, isTrue);
      expect(page.hasMore, isFalse);
    });
  });
}
