enum CartItemType { course, product, combo }

enum CartItemAvailability { available, unavailable, outOfStock }

class CartItem {
  CartItem({
    required this.id,
    required this.itemId,
    required this.type,
    required this.name,
    required this.unitPriceVnd,
    this.quantity = 1,
    this.availability = CartItemAvailability.available,
    this.availabilityMessage,
  }) {
    if (quantity < 1 || (type != CartItemType.product && quantity != 1)) {
      throw ArgumentError(
        'Courses and combos require 1; products require positive quantity.',
      );
    }
    final price = unitPriceVnd;
    if (price != null && price < 0) {
      throw ArgumentError('Price cannot be negative.');
    }
    if (availability == CartItemAvailability.available &&
        unitPriceVnd == null) {
      throw ArgumentError('Available items require a trusted price.');
    }
  }

  final int id;
  final int itemId;
  final CartItemType type;
  final String name;
  final int? unitPriceVnd;
  final int quantity;
  final CartItemAvailability availability;
  final String? availabilityMessage;
  int get subtotalVnd => (unitPriceVnd ?? 0) * quantity;

  /// Course purchase flow sells Courses and Combos (a Combo is one line granting several Courses); never Products.
  bool get isCourseCheckoutItem =>
      type == CartItemType.course || type == CartItemType.combo;

  CartItem withQuantity(int value) => CartItem(
    id: id,
    itemId: itemId,
    type: type,
    name: name,
    unitPriceVnd: unitPriceVnd,
    quantity: value,
    availability: availability,
    availabilityMessage: availabilityMessage,
  );
}

class CartSnapshot {
  const CartSnapshot({
    required this.revision,
    required this.items,
    required this.subtotalVnd,
    required this.canCheckout,
    required this.hasUnavailableItems,
    this.cartId,
  });

  final int? cartId;
  final int revision;
  final List<CartItem> items;
  final int subtotalVnd;
  final bool canCheckout;
  final bool hasUnavailableItems;

  factory CartSnapshot.fromJson(dynamic source) {
    if (source is! Map<String, dynamic>) {
      throw const FormatException('Cart data is not an object.');
    }
    final rawItems = source['items'];
    if (rawItems is! List) {
      throw const FormatException('Cart items are missing.');
    }
    final items = rawItems
        .map((raw) {
          if (raw is! Map<String, dynamic>) {
            throw const FormatException('Cart item is malformed.');
          }
          final type = switch (raw['itemType']) {
            'COURSE' => CartItemType.course,
            'PRODUCT' => CartItemType.product,
            'COMBO' => CartItemType.combo,
            _ => throw const FormatException('Unsupported cart item type.'),
          };
          final availability = switch (raw['availabilityStatus']) {
            'AVAILABLE' => CartItemAvailability.available,
            'UNAVAILABLE' => CartItemAvailability.unavailable,
            'OUT_OF_STOCK' => CartItemAvailability.outOfStock,
            _ => throw const FormatException('Unsupported cart availability.'),
          };
          final quantity = _cartInt(raw['quantity'], 'quantity');
          if (type != CartItemType.product && quantity != 1) {
            throw const FormatException('Course and combo quantity must be one.');
          }
          return CartItem(
            id: _cartInt(raw['cartItemId'], 'cartItemId'),
            itemId: _cartInt(raw['itemId'], 'itemId'),
            type: type,
            name: raw['displayName'] is String
                ? raw['displayName'] as String
                : (type == CartItemType.combo ? 'Combo' : 'Course'),
            unitPriceVnd:
                availability != CartItemAvailability.available ||
                    raw['unitPriceVnd'] == null
                ? null
                : _cartInt(raw['unitPriceVnd'], 'unitPriceVnd'),
            quantity: quantity,
            availability: availability,
            availabilityMessage: raw['availabilityMessage'] as String?,
          );
        })
        .toList(growable: false);
    final revision = _cartInt(source['revision'], 'revision');
    final subtotal = _cartInt(source['subtotalVnd'], 'subtotalVnd');
    final canCheckout = source['canCheckout'];
    final unavailable = source['hasUnavailableItems'];
    if (revision < 0 ||
        subtotal < 0 ||
        source['currency'] != 'VND' ||
        canCheckout is! bool ||
        unavailable is! bool) {
      throw const FormatException('Cart totals are invalid.');
    }
    final actualSubtotal = items.fold<int>(
      0,
      (sum, item) => sum + item.subtotalVnd,
    );
    final actualUnavailable = items.any(
      (item) => item.availability != CartItemAvailability.available,
    );
    if (actualSubtotal != subtotal ||
        actualUnavailable != unavailable ||
        canCheckout != (items.isNotEmpty && !unavailable)) {
      throw const FormatException('Cart totals do not match its items.');
    }
    return CartSnapshot(
      cartId: source['cartId'] == null
          ? null
          : _cartInt(source['cartId'], 'cartId'),
      revision: revision,
      items: List.unmodifiable(items),
      subtotalVnd: subtotal,
      canCheckout: canCheckout,
      hasUnavailableItems: unavailable,
    );
  }
}

int _cartInt(dynamic value, String field) {
  if (value is! num || value != value.toInt()) {
    throw FormatException('Cart $field must be an integer.');
  }
  return value.toInt();
}

String formatVnd(int amount) {
  final digits = amount.toString();
  final grouped = digits.replaceAllMapped(
    RegExp(r'(\d)(?=(\d{3})+(?!\d))'),
    (match) => '${match[1]}.',
  );
  return '$grouped ₫';
}
