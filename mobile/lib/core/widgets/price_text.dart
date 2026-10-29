import 'package:flutter/material.dart';

import '../utils/format.dart';

/// Hiển thị giá: có giá giảm thì giá gốc gạch ngang + giá giảm nổi bật + nhãn phần trăm.
class PriceText extends StatelessWidget {
  const PriceText({
    super.key,
    required this.price,
    this.discountPrice,
    this.large = false,
  });

  final int price;
  final int? discountPrice;
  final bool large;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final hasDiscount = discountPrice != null && discountPrice! < price;
    final mainStyle = (large ? theme.textTheme.headlineSmall : theme.textTheme.titleMedium)
        ?.copyWith(fontWeight: FontWeight.bold, color: theme.colorScheme.primary);

    if (!hasDiscount) {
      return Text(formatPrice(price), style: mainStyle);
    }
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 8,
      runSpacing: 2,
      children: [
        Text(formatPrice(discountPrice!), style: mainStyle),
        Text(
          formatPrice(price),
          key: const ValueKey('original-price'),
          style: theme.textTheme.bodyMedium?.copyWith(
            color: theme.colorScheme.outline,
            decoration: TextDecoration.lineThrough,
          ),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
          decoration: BoxDecoration(
            color: Colors.red.shade50,
            borderRadius: BorderRadius.circular(6),
          ),
          child: Text(
            '-${discountPercent(price, discountPrice!)}%',
            style: TextStyle(
              color: Colors.red.shade700,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ],
    );
  }
}
