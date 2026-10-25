import 'cart_item.dart';
import 'cart_repository.dart';

/// Demo-only in-memory state. No HTTP, persistence, enrollment or stock claims.
class MockCartRepository implements CartRepository {
  MockCartRepository({List<CartItem>? initialItems})
    : _items = List.of(initialItems ?? _samples());

  List<CartItem> _items;

  static List<CartItem> _samples() => [
    CartItem(
      id: 1,
      itemId: 101,
      type: CartItemType.course,
      name: 'Watercolor foundations',
      unitPriceVnd: 450000,
    ),
    CartItem(
      id: 2,
      itemId: 201,
      type: CartItemType.product,
      name: 'Watercolor brush set',
      unitPriceVnd: 125000,
      quantity: 2,
    ),
    CartItem(
      id: 3,
      itemId: 202,
      type: CartItemType.product,
      name: 'Mixed media sketchbook',
      unitPriceVnd: 85000,
    ),
  ];

  List<CartItem> _snapshot() => List.unmodifiable(_items);

  @override
  Future<List<CartItem>> load() async => _snapshot();

  @override
  Future<List<CartItem>> setQuantity(int cartItemId, int quantity) async {
    final index = _items.indexWhere((item) => item.id == cartItemId);
    if (index < 0) throw StateError('Cart item not found.');
    _items[index] = _items[index].withQuantity(quantity);
    return _snapshot();
  }

  @override
  Future<List<CartItem>> remove(int cartItemId) async {
    _items.removeWhere((item) => item.id == cartItemId);
    return _snapshot();
  }

  @override
  Future<List<CartItem>> clear() async {
    _items = [];
    return _snapshot();
  }
}
