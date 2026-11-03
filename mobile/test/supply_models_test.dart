import 'package:artnest_app/features/supplies/models/supply_models.dart';
import 'package:artnest_app/models/page_response.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Đọc trang học cụ từ API (định dạng PageResponse chung)', () {
    final page = PageResponse.fromJson({
      'content': [
        {
          'id': 4,
          'sku': 'BR-SET-6',
          'name': 'Bộ cọ',
          'price': 149000,
          'thumbnailUrl': null,
          'categoryId': 4,
          'categoryName': 'Cọ vẽ',
          'active': true,
          'available': 0,
          'inStock': false,
          'lowStock': true,
          'ratingAvg': 4.5,
          'ratingCount': 2,
        },
      ],
      'totalElements': 1,
      'totalPages': 1,
      'size': 20,
      'number': 0,
      'first': true,
      'last': true,
      'empty': false,
    }, SupplyProduct.fromJson);

    final p = page.content.single;
    expect(p.inStock, isFalse);
    expect(p.price, 149000);
    expect(p.stockOnHand, isNull); // học viên không thấy số tồn chi tiết
    expect(page.last, isTrue);
  });

  test('Đọc chuyến giao có quà và trạng thái kế tiếp', () {
    final s = Shipment.fromJson({
      'id': 1,
      'orderId': 99,
      'userId': 2,
      'recipientName': 'A',
      'recipientPhone': '090',
      'shippingAddress': 'HCM',
      'status': 'FAILED',
      'nextStatuses': ['SHIPPING', 'RETURNED'],
      'attempts': 1,
      'totalQuantity': 2,
      'items': [
        {'productId': 6, 'sku': 'KIT', 'productName': 'Quà', 'quantity': 1, 'gift': true},
        {'productId': 4, 'sku': 'BR', 'productName': 'Cọ', 'quantity': 1, 'gift': false},
      ],
    });

    expect(s.status, ShipmentStatus.failed);
    expect(s.nextStatuses, [ShipmentStatus.shipping, ShipmentStatus.returned]);
    expect(s.hasGift, isTrue);
  });

  test('Đọc tóm tắt đánh giá', () {
    final r = ReviewSummary.fromJson({
      'average': 4.5,
      'count': 2,
      'histogram': {'5': 1, '4': 1, '3': 0, '2': 0, '1': 0},
    });
    expect(r.histogram[5], 1);
    expect(r.count, 2);
  });
}
