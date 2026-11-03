import 'package:flutter/material.dart';

import 'supply_format.dart';
import '../../models/supply_models.dart';

class SupplyImage extends StatelessWidget {
  final String? url;
  final double? size;
  final BorderRadius borderRadius;

  const SupplyImage({
    super.key,
    this.url,
    this.size,
    this.borderRadius = const BorderRadius.all(Radius.circular(12)),
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final placeholder = Container(
      width: size,
      height: size,
      color: scheme.surfaceContainerHighest,
      alignment: Alignment.center,
      child: Icon(Icons.brush_outlined, color: scheme.outline, size: (size ?? 80) / 2.5),
    );
    return ClipRRect(
      borderRadius: borderRadius,
      child: url == null || url!.isEmpty
          ? placeholder
          : Image.network(
              url!,
              width: size,
              height: size,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) => placeholder,
              loadingBuilder: (_, child, progress) => progress == null ? child : placeholder,
            ),
    );
  }
}

class StockBadge extends StatelessWidget {
  final int available;
  final bool lowStock;
  const StockBadge({super.key, required this.available, this.lowStock = false});

  @override
  Widget build(BuildContext context) {
    final (label, color) = available <= 0
        ? ('Hết hàng', Colors.red)
        : lowStock
        ? ('Chỉ còn $available', Colors.orange)
        : ('Còn hàng', Colors.green);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label,
        style: TextStyle(color: color.shade700, fontSize: 12, fontWeight: FontWeight.w600),
      ),
    );
  }
}

class RatingStars extends StatelessWidget {
  final double rating;
  final double size;
  const RatingStars({super.key, required this.rating, this.size = 16});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 1; i <= 5; i++)
          Icon(
            rating >= i
                ? Icons.star_rounded
                : rating >= i - 0.5
                ? Icons.star_half_rounded
                : Icons.star_outline_rounded,
            size: size,
            color: Colors.amber.shade700,
          ),
      ],
    );
  }
}

class RatingInput extends StatelessWidget {
  final int value;
  final ValueChanged<int> onChanged;
  const RatingInput({super.key, required this.value, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        for (var i = 1; i <= 5; i++)
          IconButton(
            tooltip: '$i sao',
            iconSize: 36,
            onPressed: () => onChanged(i),
            icon: Icon(
              value >= i ? Icons.star_rounded : Icons.star_outline_rounded,
              color: Colors.amber.shade700,
            ),
          ),
      ],
    );
  }
}

class ShipmentStatusChip extends StatelessWidget {
  final ShipmentStatus status;
  const ShipmentStatusChip({super.key, required this.status});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: status.color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(status.icon, size: 14, color: status.color.shade700),
          const SizedBox(width: 4),
          Text(
            status.label,
            style: TextStyle(color: status.color.shade700, fontSize: 12, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}

class GiftTag extends StatelessWidget {
  const GiftTag({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: Colors.pink.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.card_giftcard, size: 12, color: Colors.pink.shade700),
          const SizedBox(width: 3),
          Text(
            'Quà kèm khóa',
            style: TextStyle(color: Colors.pink.shade700, fontSize: 11, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}

class ProductCard extends StatelessWidget {
  final SupplyProduct product;
  final VoidCallback onTap;
  const ProductCard({super.key, required this.product, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Card(
      clipBehavior: Clip.antiAlias,
      margin: EdgeInsets.zero,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            AspectRatio(
              aspectRatio: 1,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  SupplyImage(url: product.thumbnailUrl, borderRadius: BorderRadius.zero),
                  if (!product.inStock)
                    Container(
                      color: Colors.black45,
                      alignment: Alignment.center,
                      child: const Text(
                        'Hết hàng',
                        style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
                      ),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(product.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: text.titleSmall),
                  const SizedBox(height: 4),
                  Text(
                    formatVnd(product.price),
                    style: text.titleSmall?.copyWith(
                      color: Theme.of(context).colorScheme.primary,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      if (product.ratingCount > 0) ...[
                        Icon(Icons.star_rounded, size: 14, color: Colors.amber.shade700),
                        Text(
                          ' ${product.ratingAvg.toStringAsFixed(1)} (${product.ratingCount})',
                          style: text.bodySmall,
                        ),
                      ] else
                        Text('Chưa có đánh giá', style: text.bodySmall),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Dòng thời gian trạng thái giao hàng.
class ShipmentTimeline extends StatelessWidget {
  final Shipment shipment;
  const ShipmentTimeline({super.key, required this.shipment});

  @override
  Widget build(BuildContext context) {
    final s = shipment;
    final steps = <(ShipmentStatus, String?)>[
      (ShipmentStatus.preparing, s.createdAt),
      if (s.cancelledAt != null) (ShipmentStatus.cancelled, s.cancelledAt),
      if (s.shippedAt != null || s.cancelledAt == null) (ShipmentStatus.shipping, s.shippedAt),
      if (s.failedAt != null) (ShipmentStatus.failed, s.failedAt),
      if (s.returnedAt != null) (ShipmentStatus.returned, s.returnedAt),
      if (s.cancelledAt == null && s.returnedAt == null) (ShipmentStatus.delivered, s.deliveredAt),
    ];
    return Column(
      children: [
        for (var i = 0; i < steps.length; i++)
          _TimelineRow(
            status: steps[i].$1,
            time: steps[i].$2,
            done: steps[i].$2 != null,
            current: steps[i].$1 == s.status,
            last: i == steps.length - 1,
          ),
      ],
    );
  }
}

class _TimelineRow extends StatelessWidget {
  final ShipmentStatus status;
  final String? time;
  final bool done;
  final bool current;
  final bool last;

  const _TimelineRow({
    required this.status,
    required this.time,
    required this.done,
    required this.current,
    required this.last,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = done ? status.color.shade600 : scheme.outlineVariant;
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Column(
            children: [
              Icon(done ? status.icon : Icons.radio_button_unchecked, color: color, size: 22),
              if (!last) Expanded(child: Container(width: 2, color: color.withValues(alpha: 0.5))),
            ],
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    status.label,
                    style: TextStyle(
                      fontWeight: current ? FontWeight.bold : FontWeight.normal,
                      color: done ? null : scheme.outline,
                    ),
                  ),
                  if (time != null) Text(formatDateTime(time), style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
