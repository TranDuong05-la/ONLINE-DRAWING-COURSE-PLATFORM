import 'package:dio/dio.dart';
import 'package:file_selector/file_selector.dart' show XFile;

import 'api_client.dart';

/// Cầu nối cho các repository của phần KHÓA HỌC dùng chung [ApiClient] của app:
/// tự gắn token đăng nhập, tự refresh token khi hết hạn, đổi lỗi mạng/HTTP sang [ApiException].
/// Mỗi hàm trả về phần `data` đã bóc ra khỏi ApiResponse { success, message, data }.
class CourseApi {
  CourseApi._();

  static final CourseApi instance = CourseApi._();

  ApiClient get _client => ApiClient();

  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async {
    final res = await _client.get<dynamic>(path, queryParameters: query, fromJsonT: (j) => j);
    return res.data;
  }

  Future<dynamic> post(String path, {Object? body}) async {
    final res = await _client.post<dynamic>(path, data: body, fromJsonT: (j) => j);
    return res.data;
  }

  Future<dynamic> put(String path, {Object? body}) async {
    final res = await _client.put<dynamic>(path, data: body, fromJsonT: (j) => j);
    return res.data;
  }

  Future<dynamic> patch(String path, {Object? body}) async {
    final res = await _client.patch<dynamic>(path, data: body, fromJsonT: (j) => j);
    return res.data;
  }

  Future<dynamic> delete(String path) async {
    final res = await _client.delete<dynamic>(path, fromJsonT: (j) => j);
    return res.data;
  }

  /// Upload file (multipart, field `file`). [onProgress] nhận giá trị 0..1.
  /// Đọc file theo luồng từ [XFile] nên chạy được cả bản web (file chọn trên web không có đường dẫn
  /// thật, MultipartFile.fromFile chỉ chạy trên Android/iOS/desktop) mà không nạp cả file vào RAM ở mobile.
  Future<dynamic> upload(
    String path, {
    required XFile file,
    void Function(double progress)? onProgress,
  }) async {
    final form = FormData.fromMap({
      'file': MultipartFile.fromStream(file.openRead, await file.length(), filename: file.name),
    });
    final res = await _client.postMultipart<dynamic>(
      path,
      formData: form,
      onProgress: onProgress,
      fromJsonT: (j) => j,
    );
    return res.data;
  }
}
