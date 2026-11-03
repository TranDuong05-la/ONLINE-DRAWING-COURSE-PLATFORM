import 'package:dio/dio.dart';

import '../core/network/api_client.dart';
import '../core/storage/token_storage.dart';
import '../models/learning_models.dart';

/// Học tập (phần C): khóa của tôi, bài học, tiến độ, bài tập (/api/learning/**) và màn admin chấm bài / soạn đề.
/// Mọi response được bọc trong ApiResponse; hàm [_data] lấy phần `data`.
class LearningRepository {
  final ApiClient _api;
  final TokenStorage _tokens;

  LearningRepository({ApiClient? apiClient, TokenStorage? tokenStorage})
      : _api = apiClient ?? ApiClient(),
        _tokens = tokenStorage ?? TokenStorage();

  Future<T> _data<T>(Future<dynamic> call, T Function(dynamic json) parse) async {
    final res = await call;
    return parse(res.data);
  }

  // ApiClient chỉ đọc trường data khi có fromJsonT, nên luôn truyền hàm trả nguyên JSON.
  static dynamic _raw(dynamic j) => j;
  Future<dynamic> _get(String path, {Map<String, dynamic>? queryParameters}) =>
      _api.get<dynamic>(path, queryParameters: queryParameters, fromJsonT: _raw);
  Future<dynamic> _put(String path, {dynamic data}) => _api.put<dynamic>(path, data: data, fromJsonT: _raw);
  Future<dynamic> _post(String path, {dynamic data}) => _api.post<dynamic>(path, data: data, fromJsonT: _raw);
  Future<dynamic> _multipart(String path, {required FormData formData}) =>
      _api.postMultipart<dynamic>(path, formData: formData, fromJsonT: _raw);

  Map<String, dynamic> _map(dynamic j) => j as Map<String, dynamic>;
  List<T> _list<T>(dynamic j, T Function(Map<String, dynamic>) f) =>
      (j as List? ?? []).map((e) => f(e as Map<String, dynamic>)).toList();

  // ---------------------------------------------------------------- học viên

  Future<List<MyCourse>> getMyCourses() =>
      _data(_get('/api/learning/courses'), (j) => _list(j, MyCourse.fromJson));

  Future<CourseDetail> getCourseDetail(int courseId) =>
      _data(_get('/api/learning/courses/$courseId'), (j) => CourseDetail.fromJson(_map(j)));

  Future<LessonContent> getLesson(int lessonId) =>
      _data(_get('/api/learning/lessons/$lessonId'), (j) => LessonContent.fromJson(_map(j)));

  /// [watchedDeltaSeconds] = số giây thực sự đã phát kể từ lần báo thành công trước
  /// (server chỉ cộng thời gian đã phát, tua tới không được tính).
  Future<ProgressResult> saveProgress(int lessonId,
          {required int positionSeconds, required int watchedDeltaSeconds}) =>
      _data(
          _put('/api/learning/lessons/$lessonId/progress', data: {
            'positionSeconds': positionSeconds,
            'watchedDeltaSeconds': watchedDeltaSeconds,
          }),
          (j) => ProgressResult.fromJson(_map(j)));

  Future<AssignmentInfo> getAssignment(int lessonId) =>
      _data(_get('/api/learning/lessons/$lessonId/assignment'), (j) => AssignmentInfo.fromJson(_map(j)));

  Future<Submission> submitAssignment(int lessonId,
      {required String filePath, required String mimeType, String? note}) async {
    final form = FormData.fromMap({
      'image': await MultipartFile.fromFile(filePath, contentType: DioMediaType.parse(mimeType)),
      if (note != null && note.trim().isNotEmpty) 'note': note.trim(),
    });
    return _data(_multipart('/api/learning/lessons/$lessonId/submissions', formData: form),
        (j) => Submission.fromJson(_map(j)));
  }

  /// "Bài tập của tôi": mọi đề của các khóa đang học, kèm trạng thái lần nộp mới nhất.
  Future<List<MyAssignment>> getMyAssignments() =>
      _data(_get('/api/learning/assignments'), (j) => _list(j, MyAssignment.fromJson));

  // ---------------------------------------------------------------- bình luận video

  Future<LessonComments> getLessonComments(int lessonId) =>
      _data(_get('/api/learning/lessons/$lessonId/comments'), (j) => LessonComments.fromJson(_map(j)));

  /// [parentId] = bình luận gốc khi trả lời.
  Future<LessonComment> postLessonComment(int lessonId, String body, {int? parentId}) => _data(
      _post('/api/learning/lessons/$lessonId/comments', data: {'body': body, 'parentId': parentId}),
      (j) => LessonComment.fromJson(_map(j)));

  /// Hộp thư bình luận (chỉ Admin / Nhân viên / Giảng viên).
  Future<CommentInboxPage> getCommentInbox({bool unansweredOnly = false, int page = 0, int size = 30}) => _data(
      _get('/api/learning/comments/inbox',
          queryParameters: {'unanswered': unansweredOnly, 'page': page, 'size': size}),
      (j) => CommentInboxPage.fromJson(_map(j)));

  // ---------------------------------------------------------------- thông báo (chuông)

  Future<List<NotificationItem>> getNotifications() =>
      _data(_get('/api/learning/notifications'), (j) => _list(j, NotificationItem.fromJson));

  Future<int> getUnreadCount() =>
      _data(_get('/api/learning/notifications/unread-count'), (j) => (_map(j)['unread'] as num).toInt());

  Future<void> markNotificationRead(int id) => _post('/api/learning/notifications/$id/read');

  Future<void> markAllNotificationsRead() => _post('/api/learning/notifications/read-all');

  // ---------------------------------------------------------------- admin

  /// Chứng nhận đã cấp cho học viên (lọc theo khóa, tìm theo tên học viên / mã).
  Future<List<AdminCertificate>> adminCertificates({int? courseId, String? query}) => _data(
      _get('/api/admin/certificates', queryParameters: {
        'courseId': ?courseId,
        if (query != null && query.trim().isNotEmpty) 'q': query.trim(),
        'size': 50,
      }),
      (j) => _list(_map(j)['items'], AdminCertificate.fromJson));

  /// Link mở PDF chứng nhận (admin), đổi host theo địa chỉ server app đang dùng.
  Future<String> adminCertificateLink(int id) => _data(_get('/api/admin/certificates/$id/download-link'), (j) {
        final link = Uri.parse(_map(j)['url'] as String);
        final base = Uri.parse(_api.baseUrl);
        return link.replace(scheme: base.scheme, host: base.host, port: base.port).toString();
      });

  Future<SubmissionSummary> adminSummary() =>
      _data(_get('/api/admin/submissions/summary'), (j) => SubmissionSummary.fromJson(_map(j)));

  Future<List<CourseOption>> adminSubmissionCourses() =>
      _data(_get('/api/admin/submissions/courses'), (j) => _list(j, CourseOption.fromJson));

  Future<SubmissionPage> adminSubmissions({int? courseId, String? status, int page = 0, int size = 20}) => _data(
      _get('/api/admin/submissions', queryParameters: {
        'courseId': ?courseId,
        'status': ?status,
        'page': page,
        'size': size,
      }),
      (j) => SubmissionPage.fromJson(_map(j)));

  Future<AdminSubmission> adminSubmission(int id) =>
      _data(_get('/api/admin/submissions/$id'), (j) => AdminSubmission.fromJson(_map(j)));

  Future<AdminSubmission> review(int id, {required String decision, String? feedback, double? score}) => _data(
      _post('/api/admin/submissions/$id/review', data: {
        'decision': decision,
        'feedback': feedback,
        'score': score,
      }),
      (j) => AdminSubmission.fromJson(_map(j)));

  /// Danh sách khóa cho màn soạn đề: lấy từ API quản trị khóa học của B.
  /// Học viên của một khóa (đang học trước) để admin cấp / thu hồi quyền học thủ công.
  Future<List<AdminEnrollment>> adminEnrollments(int courseId) => _data(
      _get('/api/admin/learning/courses/$courseId/enrollments'), (j) => _list(j, AdminEnrollment.fromJson));

  Future<AdminEnrollment> grantEnrollment(int courseId, String email) => _data(
      _post('/api/admin/learning/courses/$courseId/enrollments', data: {'email': email}),
      (j) => AdminEnrollment.fromJson(_map(j)));

  /// Chỉ thu hồi được quyền cấp tay; quyền từ đơn hàng phải hoàn tiền qua phần D.
  Future<AdminEnrollment> revokeEnrollment(int enrollmentId, {String? reason}) => _data(
      _post('/api/admin/learning/enrollments/$enrollmentId/revoke', data: {'reason': reason}),
      (j) => AdminEnrollment.fromJson(_map(j)));

  Future<List<CourseOption>> adminAllCourses() => _data(
      _get('/api/admin/courses', queryParameters: {'page': 0, 'size': 100}),
      (j) => _list(_map(j)['content'], (c) => CourseOption((c['id'] as num).toInt(), c['title'] as String? ?? '')));

  Future<List<LessonAssignmentAdmin>> adminLessonAssignments(int courseId) => _data(
      _get('/api/admin/learning/courses/$courseId/assignments'),
      (j) => _list(j, LessonAssignmentAdmin.fromJson));

  Future<LessonAssignmentAdmin> saveAssignment(int lessonId,
          {required String title, String? instructions, required bool required, required bool active}) =>
      _data(
          _put('/api/admin/lessons/$lessonId/assignment', data: {
            'title': title,
            'instructions': instructions,
            'required': required,
            'active': active,
          }),
          (j) => LessonAssignmentAdmin.fromJson(_map(j)));

  // ---------------------------------------------------------------- media ngoài Dio (video, Image.network)

  String url(String path) => _api.url(path);

  Future<Map<String, String>> authHeaders() async {
    final token = await _tokens.getAccessToken();
    return token == null || token.isEmpty ? {} : {'Authorization': 'Bearer $token'};
  }
}
