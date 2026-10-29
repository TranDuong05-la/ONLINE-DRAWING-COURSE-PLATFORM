// Các hàm định dạng hiển thị.

/// 450000 -> "450.000 ₫" (VND, dấu chấm ngăn cách hàng nghìn).
String formatPrice(int amount) {
  final negative = amount < 0;
  final digits = amount.abs().toString();
  final buffer = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) buffer.write('.');
    buffer.write(digits[i]);
  }
  return '${negative ? '-' : ''}$buffer ₫';
}

/// 45 -> "45 giây", 150 -> "2 phút 30 giây", 3900 -> "1 giờ 5 phút".
String formatDuration(int seconds) {
  if (seconds <= 0) return '0 giây';
  if (seconds < 60) return '$seconds giây';
  if (seconds < 3600) {
    final m = seconds ~/ 60;
    final s = seconds % 60;
    return s == 0 ? '$m phút' : '$m phút $s giây';
  }
  final h = seconds ~/ 3600;
  final m = (seconds % 3600) ~/ 60;
  return m == 0 ? '$h giờ' : '$h giờ $m phút';
}

String levelLabel(String level) {
  switch (level) {
    case 'BASIC':
      return 'Cơ bản';
    case 'ADVANCED':
      return 'Nâng cao';
    default:
      return level;
  }
}

String statusLabel(String status) {
  switch (status) {
    case 'DRAFT':
      return 'Nháp';
    case 'PUBLISHED':
      return 'Đang bán';
    case 'HIDDEN':
      return 'Ngừng bán';
    default:
      return status;
  }
}

/// Phần trăm giảm giá làm tròn, ví dụ 600000 -> 449000 là "-25%".
int discountPercent(int price, int discountPrice) {
  if (price <= 0 || discountPrice >= price) return 0;
  return ((price - discountPrice) * 100 / price).round();
}
