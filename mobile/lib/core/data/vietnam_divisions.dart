class VietnamDivision {
  final int code;
  final String name;
  final String? divisionType;

  const VietnamDivision({
    required this.code,
    required this.name,
    this.divisionType,
  });

  factory VietnamDivision.fromJson(Map<String, dynamic> json) {
    return VietnamDivision(
      code: json['code'] as int,
      name: json['name'] as String,
      divisionType: json['division_type'] as String?,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'code': code,
      'name': name,
      if (divisionType != null) 'division_type': divisionType,
    };
  }

  @override
  String toString() => name;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is VietnamDivision &&
          runtimeType == other.runtimeType &&
          code == other.code;

  @override
  int get hashCode => code.hashCode;
}

/// Danh sách 63 Tỉnh / Thành phố trực thuộc Trung ương của Việt Nam
const List<VietnamDivision> kVietnamProvinces = [
  VietnamDivision(code: 1, name: 'Thành phố Hà Nội', divisionType: 'thành phố trung ương'),
  VietnamDivision(code: 2, name: 'Tỉnh Hà Giang', divisionType: 'tỉnh'),
  VietnamDivision(code: 4, name: 'Tỉnh Cao Bằng', divisionType: 'tỉnh'),
  VietnamDivision(code: 6, name: 'Tỉnh Bắc Kạn', divisionType: 'tỉnh'),
  VietnamDivision(code: 8, name: 'Tỉnh Tuyên Quang', divisionType: 'tỉnh'),
  VietnamDivision(code: 10, name: 'Tỉnh Lào Cai', divisionType: 'tỉnh'),
  VietnamDivision(code: 11, name: 'Tỉnh Điện Biên', divisionType: 'tỉnh'),
  VietnamDivision(code: 12, name: 'Tỉnh Lai Châu', divisionType: 'tỉnh'),
  VietnamDivision(code: 14, name: 'Tỉnh Sơn La', divisionType: 'tỉnh'),
  VietnamDivision(code: 15, name: 'Tỉnh Yên Bái', divisionType: 'tỉnh'),
  VietnamDivision(code: 17, name: 'Tỉnh Hoà Bình', divisionType: 'tỉnh'),
  VietnamDivision(code: 19, name: 'Tỉnh Thái Nguyên', divisionType: 'tỉnh'),
  VietnamDivision(code: 20, name: 'Tỉnh Lạng Sơn', divisionType: 'tỉnh'),
  VietnamDivision(code: 22, name: 'Tỉnh Quảng Ninh', divisionType: 'tỉnh'),
  VietnamDivision(code: 24, name: 'Tỉnh Bắc Giang', divisionType: 'tỉnh'),
  VietnamDivision(code: 25, name: 'Tỉnh Phú Thọ', divisionType: 'tỉnh'),
  VietnamDivision(code: 26, name: 'Tỉnh Vĩnh Phúc', divisionType: 'tỉnh'),
  VietnamDivision(code: 27, name: 'Tỉnh Bắc Ninh', divisionType: 'tỉnh'),
  VietnamDivision(code: 30, name: 'Tỉnh Hải Dương', divisionType: 'tỉnh'),
  VietnamDivision(code: 31, name: 'Thành phố Hải Phòng', divisionType: 'thành phố trung ương'),
  VietnamDivision(code: 33, name: 'Tỉnh Hưng Yên', divisionType: 'tỉnh'),
  VietnamDivision(code: 34, name: 'Tỉnh Thái Bình', divisionType: 'tỉnh'),
  VietnamDivision(code: 35, name: 'Tỉnh Hà Nam', divisionType: 'tỉnh'),
  VietnamDivision(code: 36, name: 'Tỉnh Nam Định', divisionType: 'tỉnh'),
  VietnamDivision(code: 37, name: 'Tỉnh Ninh Bình', divisionType: 'tỉnh'),
  VietnamDivision(code: 38, name: 'Tỉnh Thanh Hóa', divisionType: 'tỉnh'),
  VietnamDivision(code: 40, name: 'Tỉnh Nghệ An', divisionType: 'tỉnh'),
  VietnamDivision(code: 42, name: 'Tỉnh Hà Tĩnh', divisionType: 'tỉnh'),
  VietnamDivision(code: 44, name: 'Tỉnh Quảng Bình', divisionType: 'tỉnh'),
  VietnamDivision(code: 45, name: 'Tỉnh Quảng Trị', divisionType: 'tỉnh'),
  VietnamDivision(code: 46, name: 'Thành phố Huế', divisionType: 'thành phố trung ương'),
  VietnamDivision(code: 48, name: 'Thành phố Đà Nẵng', divisionType: 'thành phố trung ương'),
  VietnamDivision(code: 49, name: 'Tỉnh Quảng Nam', divisionType: 'tỉnh'),
  VietnamDivision(code: 51, name: 'Tỉnh Quảng Ngãi', divisionType: 'tỉnh'),
  VietnamDivision(code: 52, name: 'Tỉnh Bình Định', divisionType: 'tỉnh'),
  VietnamDivision(code: 54, name: 'Tỉnh Phú Yên', divisionType: 'tỉnh'),
  VietnamDivision(code: 56, name: 'Tỉnh Khánh Hòa', divisionType: 'tỉnh'),
  VietnamDivision(code: 58, name: 'Tỉnh Ninh Thuận', divisionType: 'tỉnh'),
  VietnamDivision(code: 60, name: 'Tỉnh Bình Thuận', divisionType: 'tỉnh'),
  VietnamDivision(code: 62, name: 'Tỉnh Kon Tum', divisionType: 'tỉnh'),
  VietnamDivision(code: 64, name: 'Tỉnh Gia Lai', divisionType: 'tỉnh'),
  VietnamDivision(code: 66, name: 'Tỉnh Đắk Lắk', divisionType: 'tỉnh'),
  VietnamDivision(code: 67, name: 'Tỉnh Đắk Nông', divisionType: 'tỉnh'),
  VietnamDivision(code: 68, name: 'Tỉnh Lâm Đồng', divisionType: 'tỉnh'),
  VietnamDivision(code: 70, name: 'Tỉnh Bình Phước', divisionType: 'tỉnh'),
  VietnamDivision(code: 72, name: 'Tỉnh Tây Ninh', divisionType: 'tỉnh'),
  VietnamDivision(code: 74, name: 'Tỉnh Bình Dương', divisionType: 'tỉnh'),
  VietnamDivision(code: 75, name: 'Tỉnh Đồng Nai', divisionType: 'tỉnh'),
  VietnamDivision(code: 77, name: 'Tỉnh Bà Rịa - Vũng Tàu', divisionType: 'tỉnh'),
  VietnamDivision(code: 79, name: 'Thành phố Hồ Chí Minh', divisionType: 'thành phố trung ương'),
  VietnamDivision(code: 80, name: 'Tỉnh Long An', divisionType: 'tỉnh'),
  VietnamDivision(code: 82, name: 'Tỉnh Tiền Giang', divisionType: 'tỉnh'),
  VietnamDivision(code: 83, name: 'Tỉnh Bến Tre', divisionType: 'tỉnh'),
  VietnamDivision(code: 84, name: 'Tỉnh Trà Vinh', divisionType: 'tỉnh'),
  VietnamDivision(code: 86, name: 'Tỉnh Vĩnh Long', divisionType: 'tỉnh'),
  VietnamDivision(code: 87, name: 'Tỉnh Đồng Tháp', divisionType: 'tỉnh'),
  VietnamDivision(code: 89, name: 'Tỉnh An Giang', divisionType: 'tỉnh'),
  VietnamDivision(code: 91, name: 'Tỉnh Kiên Giang', divisionType: 'tỉnh'),
  VietnamDivision(code: 92, name: 'Thành phố Cần Thơ', divisionType: 'thành phố trung ương'),
  VietnamDivision(code: 93, name: 'Tỉnh Hậu Giang', divisionType: 'tỉnh'),
  VietnamDivision(code: 94, name: 'Tỉnh Sóc Trăng', divisionType: 'tỉnh'),
  VietnamDivision(code: 95, name: 'Tỉnh Bạc Liêu', divisionType: 'tỉnh'),
  VietnamDivision(code: 96, name: 'Tỉnh Cà Mau', divisionType: 'tỉnh'),
];
