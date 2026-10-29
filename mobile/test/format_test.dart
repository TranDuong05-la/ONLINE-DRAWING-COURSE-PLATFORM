import 'package:artnest_app/core/network/media_url.dart';
import 'package:artnest_app/core/utils/format.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('formatPrice', () {
    test('ngăn cách hàng nghìn bằng dấu chấm', () {
      expect(formatPrice(0), '0 ₫');
      expect(formatPrice(999), '999 ₫');
      expect(formatPrice(1000), '1.000 ₫');
      expect(formatPrice(450000), '450.000 ₫');
      expect(formatPrice(1234567), '1.234.567 ₫');
    });
  });

  group('formatDuration', () {
    test('giây, phút, giờ', () {
      expect(formatDuration(0), '0 giây');
      expect(formatDuration(45), '45 giây');
      expect(formatDuration(120), '2 phút');
      expect(formatDuration(150), '2 phút 30 giây');
      expect(formatDuration(3600), '1 giờ');
      expect(formatDuration(3900), '1 giờ 5 phút');
    });
  });

  group('discountPercent', () {
    test('làm tròn phần trăm giảm', () {
      expect(discountPercent(600000, 449000), 25);
      expect(discountPercent(100000, 50000), 50);
    });

    test('không giảm thì trả 0', () {
      expect(discountPercent(100000, 100000), 0);
      expect(discountPercent(100000, 120000), 0);
      expect(discountPercent(0, 0), 0);
    });
  });

  group('nhãn', () {
    test('cấp độ và trạng thái', () {
      expect(levelLabel('BASIC'), 'Cơ bản');
      expect(levelLabel('ADVANCED'), 'Nâng cao');
      expect(statusLabel('DRAFT'), 'Nháp');
      expect(statusLabel('PUBLISHED'), 'Đang bán');
      expect(statusLabel('HIDDEN'), 'Ngừng bán');
    });
  });

  group('resolveMediaUrl', () {
    const base = 'http://10.0.2.2:8081';

    test('ghép đường dẫn tương đối với địa chỉ server', () {
      expect(resolveMediaUrl('/uploads/a.mp4', baseUrl: base), 'http://10.0.2.2:8081/uploads/a.mp4');
    });

    test('giữ nguyên link ngoài', () {
      expect(resolveMediaUrl('https://example.com/v.mp4', baseUrl: base), 'https://example.com/v.mp4');
      expect(resolveMediaUrl('http://example.com/v.mp4', baseUrl: base), 'http://example.com/v.mp4');
    });

    test('rỗng hoặc null thì trả chuỗi rỗng', () {
      expect(resolveMediaUrl(null, baseUrl: base), '');
      expect(resolveMediaUrl('', baseUrl: base), '');
    });
  });
}