import '../../../core/network/api_client.dart';
import '../../../core/network/api_exceptions.dart';

/// Điểm nối với giỏ hàng của D.
///
/// Theo CART_API_CONTRACT của D:
///   `POST /api/cart/items  { "itemType": "PRODUCT", "itemId": productId, "quantity": n }`
/// Backend D lấy giá và số còn bán qua SupplyFulfillmentApi.findProductForCart(productId).
///
/// Khi ghép code của D, nếu D đổi cách gọi thì chỉ cần sửa file này.
class CartBridge {
  CartBridge._();
  static final CartBridge instance = CartBridge._();

  Future<void> addProduct(int productId, int quantity) async {
    try {
      final res = await ApiClient().post(
        '/api/cart/items',
        data: {'itemType': 'PRODUCT', 'itemId': productId, 'quantity': quantity},
      );
      if (!res.success) throw ApiException(message: res.firstErrorMessage);
    } on ApiException catch (e) {
      // Chưa có API giỏ của D: Spring trả 404 hoặc 500 (không tìm thấy đường dẫn)
      if (e.statusCode == 404 || e.statusCode == 500) throw CartNotReadyException();
      rethrow;
    }
  }
}

class CartNotReadyException implements Exception {
  @override
  String toString() => 'Giỏ hàng (phần D) chưa được ghép vào app';
}
