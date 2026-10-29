import 'api_client.dart';

/// Backend lưu đường dẫn tương đối của file đã upload ("/uploads/abc.mp4").
/// Hàm này ghép với địa chỉ server đang dùng (có thể đổi lúc chạy bằng hộp thoại cấu hình máy chủ).
/// Link ngoài (http/https) được giữ nguyên. [baseUrl] chỉ để test; bình thường lấy từ [ApiClient].
String resolveMediaUrl(String? pathOrUrl, {String? baseUrl}) {
  if (pathOrUrl == null || pathOrUrl.isEmpty) return '';
  if (pathOrUrl.startsWith('http://') || pathOrUrl.startsWith('https://')) {
    return pathOrUrl;
  }
  return '${baseUrl ?? ApiClient().baseUrl}$pathOrUrl';
}
