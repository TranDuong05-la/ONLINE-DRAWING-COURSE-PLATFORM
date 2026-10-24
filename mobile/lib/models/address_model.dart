class AddressModel {
  final int id;
  final String receiverName;
  final String phone;
  final String province;
  final String district;
  final String ward;
  final String streetAddress;
  final bool isDefault;
  final DateTime? createdAt;

  AddressModel({
    required this.id,
    required this.receiverName,
    required this.phone,
    required this.province,
    required this.district,
    required this.ward,
    required this.streetAddress,
    this.isDefault = false,
    this.createdAt,
  });

  String get fullAddress {
    final parts = [streetAddress, ward, district, province]
        .where((p) => p.trim().isNotEmpty)
        .toList();
    return parts.join(', ');
  }

  factory AddressModel.fromJson(Map<String, dynamic> json) {
    return AddressModel(
      id: json['id'] as int? ?? 0,
      receiverName: json['receiverName'] as String? ?? '',
      phone: json['phone'] as String? ?? '',
      province: json['province'] as String? ?? '',
      district: json['district'] as String? ?? '',
      ward: json['ward'] as String? ?? '',
      streetAddress: json['streetAddress'] as String? ?? '',
      isDefault: json['isDefault'] as bool? ?? false,
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'] as String)
          : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'receiverName': receiverName,
      'phone': phone,
      'province': province,
      'district': district,
      'ward': ward,
      'streetAddress': streetAddress,
      'isDefault': isDefault,
      'createdAt': createdAt?.toIso8601String(),
    };
  }

  AddressModel copyWith({
    int? id,
    String? receiverName,
    String? phone,
    String? province,
    String? district,
    String? ward,
    String? streetAddress,
    bool? isDefault,
    DateTime? createdAt,
  }) {
    return AddressModel(
      id: id ?? this.id,
      receiverName: receiverName ?? this.receiverName,
      phone: phone ?? this.phone,
      province: province ?? this.province,
      district: district ?? this.district,
      ward: ward ?? this.ward,
      streetAddress: streetAddress ?? this.streetAddress,
      isDefault: isDefault ?? this.isDefault,
      createdAt: createdAt ?? this.createdAt,
    );
  }
}

class AddressRequest {
  final String receiverName;
  final String phone;
  final String province;
  final String district;
  final String ward;
  final String streetAddress;
  final bool? isDefault;

  AddressRequest({
    required this.receiverName,
    required this.phone,
    required this.province,
    required this.district,
    required this.ward,
    required this.streetAddress,
    this.isDefault,
  });

  Map<String, dynamic> toJson() {
    final map = <String, dynamic>{
      'receiverName': receiverName.trim(),
      'phone': phone.trim(),
      'province': province.trim(),
      'district': district.trim(),
      'ward': ward.trim(),
      'streetAddress': streetAddress.trim(),
    };
    if (isDefault != null) {
      map['isDefault'] = isDefault;
    }
    return map;
  }
}
