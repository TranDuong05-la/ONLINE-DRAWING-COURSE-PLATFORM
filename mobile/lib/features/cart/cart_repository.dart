import 'cart_item.dart';

/// Returns authoritative cart snapshots after each operation. No user IDs or
/// client prices are submitted; future REST adapters use trusted authentication.
abstract interface class CartRepository {
  Future<List<CartItem>> load();
  Future<List<CartItem>> setQuantity(int cartItemId, int quantity);
  Future<List<CartItem>> remove(int cartItemId);
  Future<List<CartItem>> clear();
}
