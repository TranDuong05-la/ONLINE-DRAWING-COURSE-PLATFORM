import 'cart_item.dart';

/// Returns authoritative cart snapshots after each operation. No user IDs or
/// client prices are submitted.
abstract interface class CartRepository {
  Future<CartSnapshot> load();
  Future<CartSnapshot> addCourse(int courseId);
  Future<CartSnapshot> setQuantity(int cartItemId, int quantity);
  Future<CartSnapshot> remove(int cartItemId);
  Future<CartSnapshot> clear();
}
