import 'package:youtube_player_iframe/youtube_player_iframe.dart';

/// Nhận diện link video của bài học.
///
/// App phát được 2 loại:
/// - video đã upload lên server (đường dẫn tương đối "/uploads/..." hoặc link có chữ ký "/media/...")
///   và link trực tiếp tới file video (.mp4, .webm, .mov, .m3u8): phát bằng video_player;
/// - link YouTube (watch, youtu.be, shorts, embed): phát bằng trình phát YouTube nhúng.
/// Link trang web khác (Google Drive, Facebook...) không phát được trong app.

const _directVideoExtensions = ['.mp4', '.webm', '.mov', '.m4v', '.m3u8'];

/// Mã video YouTube (11 ký tự) nếu [url] là link YouTube, ngược lại null.
String? youtubeVideoId(String? url) {
  if (url == null || url.trim().isEmpty) return null;
  final trimmed = url.trim();
  if (!trimmed.startsWith('http')) return null;
  // convertUrlToId chỉ nhận https và không nhận tham số đứng trước v= (ví dụ ?si=...&v=...)
  final https = trimmed.replaceFirst(RegExp(r'^http://'), 'https://');
  final id = YoutubePlayerController.convertUrlToId(https);
  if (id != null) return id;
  final uri = Uri.tryParse(https);
  if (uri != null && uri.host.endsWith('youtube.com')) {
    final v = uri.queryParameters['v'];
    if (v != null && RegExp(r'^[_\-a-zA-Z0-9]{11}$').hasMatch(v)) return v;
  }
  return null;
}

/// true nếu là file video phát trực tiếp được (đã upload hoặc link tới file .mp4...).
bool isDirectVideoLink(String? url) {
  if (url == null || url.trim().isEmpty) return false;
  final value = url.trim();
  if (value.startsWith('/')) return true; // file đã upload lên server
  final path = Uri.tryParse(value)?.path.toLowerCase() ?? '';
  return _directVideoExtensions.any(path.endsWith);
}

/// Lý do link video không dùng được (để báo khi admin dán link), hoặc null nếu dùng được.
String? videoLinkProblem(String link) {
  if (youtubeVideoId(link) != null || isDirectVideoLink(link)) return null;
  return 'App chỉ phát được link YouTube hoặc link trực tiếp tới file video (.mp4, .webm). '
      'Link trang web khác (Google Drive, Facebook...) không xem được, hãy upload file video.';
}
