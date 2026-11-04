import 'cart_item.dart';

/// Returns authoritative cart snapshots after each operation. No user IDs or
/// client prices are submitted.
abstract interface class CartRepository {
  Future<CartSnapshot> load();
  Future<CartSnapshot> addCourse(int courseId);

  /// A Combo is ONE cart line (quantity 1) that grants every Course inside it.
  Future<CartSnapshot> addCombo(int comboId);
  Future<CartSnapshot> setQuantity(int cartItemId, int quantity);
  Future<CartSnapshot> remove(int cartItemId);
  Future<CartSnapshot> clear();
}

/// Separate capability for the general Product cart. Course quantities remain
/// constrained to one by the Course-only repository and backend.
abstract interface class ProductCartQuantityRepository {
  Future<CartSnapshot> setProductQuantity(int cartItemId, int quantity);
}
