import '../../../core/network/course_api.dart';
import '../models/course_models.dart';

/// Gọi API khóa học dành cho học viên.
class CourseRepository {
  final CourseApi _api = CourseApi.instance;

  /// [sort]: newest | price_asc | price_desc. [level]: BASIC | ADVANCED (null = tất cả).
  Future<PageResult<CourseSummary>> list({
    String? keyword,
    String? level,
    String sort = 'newest',
    int page = 0,
    int size = 10,
  }) async {
    final data = await _api.get('/api/courses', query: {
      if (keyword != null && keyword.trim().isNotEmpty) 'keyword': keyword.trim(),
      'level': ?level,
      'sort': sort,
      'page': page,
      'size': size,
    });
    return PageResult.fromJson(data as Map<String, dynamic>, CourseSummary.fromJson);
  }

  Future<CourseDetail> detail(int id) async {
    final data = await _api.get('/api/courses/$id');
    return CourseDetail.fromJson(data as Map<String, dynamic>);
  }
}
