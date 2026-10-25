enum CartItemType { course, product }

class CartItem {
  CartItem({
    required this.id,
    required this.itemId,
    required this.type,
    required this.name,
    required this.unitPriceVnd,
    this.quantity = 1,
  }) {
    if (quantity < 1 || (type == CartItemType.course && quantity != 1)) {
      throw ArgumentError(
        'Courses require 1; products require positive quantity.',
      );
    }
    if (unitPriceVnd < 0) throw ArgumentError('Price cannot be negative.');
  }

  final int id;
  final int itemId;
  final CartItemType type;
  final String name;
  final int unitPriceVnd;
  final int quantity;
  int get subtotalVnd => unitPriceVnd * quantity;

  CartItem withQuantity(int value) => CartItem(
    id: id,
    itemId: itemId,
    type: type,
    name: name,
    unitPriceVnd: unitPriceVnd,
    quantity: value,
  );
}

String formatVnd(int amount) {
  final digits = amount.toString();
  final grouped = digits.replaceAllMapped(
    RegExp(r'(\d)(?=(\d{3})+(?!\d))'),
    (match) => '${match[1]}.',
  );
  return '$grouped ₫';
}
