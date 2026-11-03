import 'package:artnest_app/features/cart/cart_item.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('parses the server cart snapshot and trusted Course price', () {
    final cart = CartSnapshot.fromJson({
      'cartId': 7,
      'revision': 12,
      'items': [
        {
          'cartItemId': 31,
          'itemType': 'COURSE',
          'itemId': 101,
          'quantity': 1,
          'displayName': 'Watercolor basics',
          'availabilityStatus': 'AVAILABLE',
          'unitPriceVnd': 450000,
          'lineTotalVnd': 450000,
          'availabilityMessage': null,
        },
      ],
      'subtotalVnd': 450000,
      'currency': 'VND',
      'hasUnavailableItems': false,
      'canCheckout': true,
      'availabilityWarnings': [],
    });

    expect(cart.revision, 12);
    expect(cart.items.single.name, 'Watercolor basics');
    expect(cart.items.single.unitPriceVnd, 450000);
    expect(cart.items.single.quantity, 1);
    expect(cart.canCheckout, isTrue);
  });

  test('mixed or unavailable content cannot be checked out', () {
    final cart = CartSnapshot.fromJson({
      'cartId': 7,
      'revision': 13,
      'items': [
        {
          'cartItemId': 31,
          'itemType': 'COURSE',
          'itemId': 101,
          'quantity': 1,
          'displayName': 'Watercolor basics',
          'availabilityStatus': 'AVAILABLE',
          'unitPriceVnd': 450000,
          'lineTotalVnd': 450000,
          'availabilityMessage': null,
        },
        {
          'cartItemId': 32,
          'itemType': 'PRODUCT',
          'itemId': 201,
          'quantity': 1,
          'displayName': 'Brush set',
          'availabilityStatus': 'UNAVAILABLE',
          'unitPriceVnd': null,
          'lineTotalVnd': null,
          'availabilityMessage': 'Product checkout is unavailable.',
        },
      ],
      'subtotalVnd': 450000,
      'currency': 'VND',
      'hasUnavailableItems': true,
      'canCheckout': false,
      'availabilityWarnings': [],
    });

    expect(cart.hasUnavailableItems, isTrue);
    expect(cart.canCheckout, isFalse);
    expect(cart.items.last.unitPriceVnd, isNull);
  });

  test('rejects Course quantities other than one', () {
    expect(
      () => CartSnapshot.fromJson({
        'cartId': 7,
        'revision': 1,
        'items': [
          {
            'cartItemId': 31,
            'itemType': 'COURSE',
            'itemId': 101,
            'quantity': 2,
            'displayName': 'Watercolor basics',
            'availabilityStatus': 'AVAILABLE',
            'unitPriceVnd': 450000,
            'lineTotalVnd': 900000,
            'availabilityMessage': null,
          },
        ],
        'subtotalVnd': 900000,
        'currency': 'VND',
        'hasUnavailableItems': false,
        'canCheckout': true,
        'availabilityWarnings': [],
      }),
      throwsFormatException,
    );
  });
}
