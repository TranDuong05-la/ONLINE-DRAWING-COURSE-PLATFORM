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

  CartSnapshot _snapshot() => CartSnapshot(
    revision: 0,
    items: List.unmodifiable(_items),
    subtotalVnd: _items.fold(0, (sum, item) => sum + item.subtotalVnd),
    canCheckout: _items.isNotEmpty,
    hasUnavailableItems: false,
  );

  @override
  Future<CartSnapshot> load() async => _snapshot();

  @override
  Future<CartSnapshot> addCourse(int courseId) async {
    if (courseId <= 0) throw ArgumentError.value(courseId, 'courseId');
    if (_items.any(
      (item) => item.type == CartItemType.course && item.itemId == courseId,
    )) {
      return _snapshot();
    }
    _items.add(
      CartItem(
        id: DateTime.now().microsecondsSinceEpoch,
        itemId: courseId,
        type: CartItemType.course,
        name: 'Course $courseId',
        unitPriceVnd: 0,
      ),
    );
    return _snapshot();
  }

  @override
  Future<CartSnapshot> setQuantity(int cartItemId, int quantity) async {
    final index = _items.indexWhere((item) => item.id == cartItemId);
    if (index < 0) throw StateError('Cart item not found.');
    _items[index] = _items[index].withQuantity(quantity);
    return _snapshot();
  }

  @override
  Future<CartSnapshot> remove(int cartItemId) async {
    _items.removeWhere((item) => item.id == cartItemId);
    return _snapshot();
  }

  @override
  Future<CartSnapshot> clear() async {
    _items = [];
    return _snapshot();
  }
}
