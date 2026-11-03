import 'package:dio/dio.dart';
import '../core/data/vietnam_divisions.dart';

class VietnamAddressService {
  static final VietnamAddressService _instance = VietnamAddressService._internal();
  factory VietnamAddressService() => _instance;
  VietnamAddressService._internal();

  final Dio _dio = Dio(
    BaseOptions(
      baseUrl: 'https://provinces.open-api.vn/api/v1',
      connectTimeout: const Duration(seconds: 8),
      receiveTimeout: const Duration(seconds: 8),
    ),
  );

  final Map<int, List<VietnamDivision>> _districtsCache = {};
  final Map<int, List<VietnamDivision>> _wardsCache = {};

  /// Lấy danh sách 63 tỉnh thành phố
  Future<List<VietnamDivision>> getProvinces() async {
    // Trả về ngay danh sách địa phương có sẵn để phản hồi ngay lập tức (0ms)
    return kVietnamProvinces;
  }

  /// Lấy danh sách quận/huyện theo mã tỉnh/thành
  Future<List<VietnamDivision>> getDistricts(int provinceCode) async {
    if (_districtsCache.containsKey(provinceCode)) {
      return _districtsCache[provinceCode]!;
    }

    try {
      final response = await _dio.get('/p/$provinceCode', queryParameters: {'depth': 2});
      if (response.statusCode == 200 && response.data != null) {
        final data = response.data as Map<String, dynamic>;
        final rawDistricts = data['districts'] as List<dynamic>? ?? [];
        final districts = rawDistricts
            .map((item) => VietnamDivision.fromJson(item as Map<String, dynamic>))
            .toList();
        _districtsCache[provinceCode] = districts;
        return districts;
      }
    } catch (_) {
      // Bỏ qua lỗi mạng, trả về rỗng nếu có lỗi để UI vẫn cho nhập tay
    }

    return _districtsCache[provinceCode] ?? [];
  }

  /// Lấy danh sách phường/xã theo mã quận/huyện
  Future<List<VietnamDivision>> getWards(int districtCode) async {
    if (_wardsCache.containsKey(districtCode)) {
      return _wardsCache[districtCode]!;
    }

    try {
      final response = await _dio.get('/d/$districtCode', queryParameters: {'depth': 2});
      if (response.statusCode == 200 && response.data != null) {
        final data = response.data as Map<String, dynamic>;
        final rawWards = data['wards'] as List<dynamic>? ?? [];
        final wards = rawWards
            .map((item) => VietnamDivision.fromJson(item as Map<String, dynamic>))
            .toList();
        _wardsCache[districtCode] = wards;
        return wards;
      }
    } catch (_) {
      // Bỏ qua lỗi mạng
    }

    return _wardsCache[districtCode] ?? [];
  }

  /// Tìm tỉnh/thành phố theo tên (hỗ trợ so sánh có dấu hoặc không dấu)
  VietnamDivision? findProvinceByName(String name) {
    if (name.trim().isEmpty) return null;
    final clean = removeVietnameseDiacritics(name).toLowerCase().trim();

    // 1. So sánh chính xác
    for (final p in kVietnamProvinces) {
      if (p.name.toLowerCase() == name.toLowerCase()) return p;
    }

    // 2. So sánh không dấu
    for (final p in kVietnamProvinces) {
      final pClean = removeVietnameseDiacritics(p.name).toLowerCase();
      if (pClean == clean || pClean.contains(clean) || clean.contains(pClean)) {
        return p;
      }
    }
    return null;
  }

  /// Tìm quận/huyện theo tên trong danh sách
  VietnamDivision? findDistrictByName(List<VietnamDivision> districts, String name) {
    if (name.trim().isEmpty) return null;
    final clean = removeVietnameseDiacritics(name).toLowerCase().trim();

    for (final d in districts) {
      if (d.name.toLowerCase() == name.toLowerCase()) return d;
    }

    for (final d in districts) {
      final dClean = removeVietnameseDiacritics(d.name).toLowerCase();
      if (dClean == clean || dClean.contains(clean) || clean.contains(dClean)) {
        return d;
      }
    }
    return null;
  }

  /// Lọc danh sách địa phương theo từ khóa tìm kiếm (hỗ trợ gõ không dấu)
  static List<VietnamDivision> filterDivisions(
    List<VietnamDivision> source,
    String query,
  ) {
    if (query.trim().isEmpty) return source;

    final normalizedQuery = removeVietnameseDiacritics(query).toLowerCase().trim();
    return source.where((item) {
      final normalizedName = removeVietnameseDiacritics(item.name).toLowerCase();
      return normalizedName.contains(normalizedQuery);
    }).toList();
  }

  /// Chuẩn hóa chuỗi tiếng Việt thành không dấu để tìm kiếm thân thiện
  static String removeVietnameseDiacritics(String str) {
    var result = str;
    const vietnameseMap = {
      'a': '[àáảãạăằắẳẵặâầấẩẫậ]',
      'A': '[ÀÁẢÃẠĂẰẮẲẴẶÂẦẤẨẪẬ]',
      'd': '[đ]',
      'D': '[Đ]',
      'e': '[èéẻẽẹêềếểễệ]',
      'E': '[ÈÉẺẼẸÊỀẾỂỄỆ]',
      'i': '[ìíỉĩị]',
      'I': '[ÌÍỈĨỊ]',
      'o': '[òóỏõọôồốổỗộơờớởỡợ]',
      'O': '[ÒÓỎÕỌÔỒỐỔỖỘƠỜỚỞỠỢ]',
      'u': '[ùúủũụưừứửữự]',
      'U': '[ÙÚỦŨỤƯỪỨỬỮỰ]',
      'y': '[ỳýỷỹỵ]',
      'Y': '[ỲÝỶỸỴ]',
    };

    vietnameseMap.forEach((key, pattern) {
      result = result.replaceAll(RegExp(pattern), key);
    });

    return result;
  }
}
