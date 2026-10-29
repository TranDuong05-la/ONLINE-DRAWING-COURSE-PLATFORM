import '../../../core/network/course_api.dart';
import '../models/bundle_models.dart';

/// Gọi API combo dành cho học viên.
class BundleRepository {
  final CourseApi _api = CourseApi.instance;

  Future<List<Bundle>> list() async {
    final data = await _api.get('/api/bundles');
    return (data as List).map((e) => Bundle.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<Bundle> detail(int id) async {
    final data = await _api.get('/api/bundles/$id');
    return Bundle.fromJson(data as Map<String, dynamic>);
  }
}
