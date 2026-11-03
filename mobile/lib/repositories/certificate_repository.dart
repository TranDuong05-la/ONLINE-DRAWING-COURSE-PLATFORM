import '../core/network/api_client.dart';
import '../models/certificate_model.dart';

/// Chứng nhận hoàn thành của học viên (phần C, /api/learning/certificates/**).
class CertificateRepository {
  final ApiClient _apiClient;

  CertificateRepository({ApiClient? apiClient}) : _apiClient = apiClient ?? ApiClient();

  Future<List<CertificateModel>> getMyCertificates() async {
    final res = await _apiClient.get<dynamic>('/api/learning/certificates', fromJsonT: (j) => j);
    return (res.data as List? ?? [])
        .map((e) => CertificateModel.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Link ký số ngắn hạn (khoảng 5 phút) để mở PDF mà không cần header đăng nhập.
  /// Thay host bằng địa chỉ server app đang dùng, nên link chạy được dù backend cấu hình public URL nào.
  Future<String> getDownloadLink(int certificateId) async {
    final res = await _apiClient.get<dynamic>('/api/learning/certificates/$certificateId/download-link', fromJsonT: (j) => j);
    final link = Uri.parse((res.data as Map<String, dynamic>)['url'] as String);
    final base = Uri.parse(_apiClient.baseUrl);
    return link.replace(scheme: base.scheme, host: base.host, port: base.port).toString();
  }

  Future<CertificateModel> resendEmail(int certificateId) async {
    final res = await _apiClient.post<dynamic>('/api/learning/certificates/$certificateId/resend-email', fromJsonT: (j) => j);
    return CertificateModel.fromJson(res.data as Map<String, dynamic>);
  }
}
