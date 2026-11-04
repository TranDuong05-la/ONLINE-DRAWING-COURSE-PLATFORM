import 'package:file_selector/file_selector.dart' show XFile;

import '../../../core/constants/staff_area.dart';
import '../../../core/network/course_api.dart';
import '../../bundle/models/bundle_models.dart';
import '../../course/models/course_models.dart';
import '../models/admin_models.dart';

/// Gọi API quản trị (/api/admin/**). Ở khu vực giảng viên ([StaffArea.instructor]) các hàm sửa nội dung gọi
/// /api/instructor/** thay thế; các hàm chỉ ADMIN được dùng (tạo khóa, giá, trạng thái, quà, combo) giữ nguyên.
/// TODO(A): khi có đăng nhập JWT, gắn token vào header Authorization trong ApiClient.
class CourseAdminRepository {
  final CourseApi _api = CourseApi.instance;

  // ------------------------------------------------------------------ khóa học

  Future<PageResult<CourseSummary>> listCourses({
    String? status,
    String? keyword,
    int page = 0,
    int size = 20,
  }) async {
    if (StaffArea.instructor) return _instructorCourses(status, keyword);
    final data = await _api.get('/api/admin/courses', query: {
      'status': ?status,
      if (keyword != null && keyword.trim().isNotEmpty) 'keyword': keyword.trim(),
      'page': page,
      'size': size,
    });
    return PageResult.fromJson(data as Map<String, dynamic>, CourseSummary.fromJson);
  }

  /// Khóa được phân công (server trả cả danh sách, thường ít): lọc trạng thái / tên ngay trên máy.
  Future<PageResult<CourseSummary>> _instructorCourses(String? status, String? keyword) async {
    final data = await _api.get('/api/instructor/courses');
    final q = keyword?.trim().toLowerCase() ?? '';
    final items = (data as List)
        .map((e) => CourseSummary.fromJson(e as Map<String, dynamic>))
        .where((c) => (status == null || c.status == status) && (q.isEmpty || c.title.toLowerCase().contains(q)))
        .toList();
    return PageResult(items: items, page: 0, totalPages: 1, totalElements: items.length);
  }

  Future<AdminCourseDetail> getCourse(int id) async {
    final data = await _api.get('${StaffArea.api}/courses/$id');
    return AdminCourseDetail.fromJson(data as Map<String, dynamic>);
  }

  // ------------------------------------------------------------------ giảng viên của khóa (chỉ ADMIN)

  Future<List<CourseInstructor>> listInstructors(int courseId) async {
    final data = await _api.get('/api/admin/courses/$courseId/instructors');
    return (data as List).map((e) => CourseInstructor.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> assignInstructor(int courseId, String email) =>
      _api.post('/api/admin/courses/$courseId/instructors', body: {'email': email});

  Future<void> unassignInstructor(int courseId, int userId) =>
      _api.delete('/api/admin/courses/$courseId/instructors/$userId');

  /// [id] = null thì tạo mới (POST), ngược lại cập nhật (PUT).
  Future<AdminCourseDetail> saveCourse({
    int? id,
    String? code,
    required String title,
    String? shortDescription,
    String? description,
    String? thumbnailUrl,
    required int price,
    int? discountPrice,
    required String level,
  }) async {
    final body = {
      'code': code,
      'title': title,
      'shortDescription': shortDescription,
      'description': description,
      'thumbnailUrl': thumbnailUrl,
      'price': price,
      'discountPrice': discountPrice,
      'level': level,
    };
    final data = id == null
        ? await _api.post('/api/admin/courses', body: body)
        : await _api.put('/api/admin/courses/$id', body: body);
    return AdminCourseDetail.fromJson(data as Map<String, dynamic>);
  }

  /// [status]: PUBLISHED | HIDDEN. Backend từ chối (400) nếu chưa đủ điều kiện xuất bản.
  Future<AdminCourseDetail> changeStatus(int id, String status) async {
    final data = await _api.patch('/api/admin/courses/$id/status', body: {'status': status});
    return AdminCourseDetail.fromJson(data as Map<String, dynamic>);
  }

  // ------------------------------------------------------------------ module / bài học

  Future<AdminModule> createModule(int courseId, String title) async {
    final data = await _api.post('${StaffArea.api}/courses/$courseId/modules',
        body: {'title': title, 'hidden': false});
    return AdminModule.fromJson(data as Map<String, dynamic>);
  }

  Future<AdminModule> updateModule(int moduleId, {required String title, required bool hidden}) async {
    final data = await _api.put('${StaffArea.api}/modules/$moduleId',
        body: {'title': title, 'hidden': hidden});
    return AdminModule.fromJson(data as Map<String, dynamic>);
  }

  Future<void> reorderModules(int courseId, List<int> ids) =>
      _api.put('${StaffArea.api}/courses/$courseId/modules/reorder', body: {'ids': ids});

  /// [lessonId] = null thì tạo mới trong [moduleId], ngược lại cập nhật bài đó.
  Future<AdminLesson> saveLesson({
    int? lessonId,
    required int moduleId,
    required String title,
    String? description,
    String? videoUrl,
    required int durationSeconds,
    String? materialUrl,
    required bool preview,
    required bool required,
    required bool hidden,
  }) async {
    final body = {
      'title': title,
      'description': description,
      'videoUrl': videoUrl,
      'durationSeconds': durationSeconds,
      'materialUrl': materialUrl,
      'preview': preview,
      'required': required,
      'hidden': hidden,
    };
    final data = lessonId == null
        ? await _api.post('${StaffArea.api}/modules/$moduleId/lessons', body: body)
        : await _api.put('${StaffArea.api}/lessons/$lessonId', body: body);
    return AdminLesson.fromJson(data as Map<String, dynamic>);
  }

  Future<void> reorderLessons(int moduleId, List<int> ids) =>
      _api.put('${StaffArea.api}/modules/$moduleId/lessons/reorder', body: {'ids': ids});

  // ------------------------------------------------------------------ quà tặng

  Future<List<Product>> listProducts() async {
    final data = await _api.get('/api/admin/products');
    return (data as List).map((e) => Product.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Gửi TOÀN BỘ danh sách quà mới: productId -> số lượng.
  Future<List<Kit>> replaceKits(int courseId, Map<int, int> productQuantities) async {
    final items = productQuantities.entries
        .map((e) => {'productId': e.key, 'quantity': e.value})
        .toList();
    final data = await _api.put('/api/admin/courses/$courseId/kits', body: {'items': items});
    return (data as List).map((e) => Kit.fromJson(e as Map<String, dynamic>)).toList();
  }

  // ------------------------------------------------------------------ upload

  /// Upload ảnh/video/pdf, trả về đường dẫn tương đối như "/uploads/xxx.mp4".
  Future<String> upload({
    required XFile file,
    void Function(double progress)? onProgress,
  }) async {
    final data = await _api.upload('${StaffArea.api}/uploads', file: file, onProgress: onProgress);
    return (data as Map<String, dynamic>)['url'] as String;
  }

  /// Video/PDF trên server không còn công khai: muốn xem thử phải xin link có chữ ký (hết hạn sau ~2 giờ).
  /// Link ngoài và ảnh được server trả nguyên vẹn.
  Future<String> signMedia(String path) async {
    final data = await _api.get('${StaffArea.api}/media/sign', query: {'path': path});
    return (data as Map<String, dynamic>)['url'] as String;
  }

  // ------------------------------------------------------------------ combo

  Future<List<AdminBundle>> listBundles() async {
    final data = await _api.get('/api/admin/bundles');
    return (data as List).map((e) => AdminBundle.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<AdminBundle> saveBundle({
    int? id,
    String? code,
    required String title,
    String? description,
    required int price,
    required List<int> courseIds,
  }) async {
    final body = {
      'code': code,
      'title': title,
      'description': description,
      'price': price,
      'courseIds': courseIds,
    };
    final data = id == null
        ? await _api.post('/api/admin/bundles', body: body)
        : await _api.put('/api/admin/bundles/$id', body: body);
    return AdminBundle.fromJson(data as Map<String, dynamic>);
  }

  Future<AdminBundle> setBundleActive(int id, bool active) async {
    final data = await _api.patch('/api/admin/bundles/$id/active', body: {'active': active});
    return AdminBundle.fromJson(data as Map<String, dynamic>);
  }
}
