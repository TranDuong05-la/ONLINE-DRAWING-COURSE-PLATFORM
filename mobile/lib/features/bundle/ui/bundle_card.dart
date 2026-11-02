import 'package:flutter/material.dart';

import '../../../core/utils/format.dart';
import '../../../core/widgets/app_image.dart';
import '../../../core/widgets/small_badge.dart';
import '../models/bundle_models.dart';

/// Thẻ combo ở danh sách: tên, số khóa, giá combo, giá mua lẻ gạch ngang và số tiền tiết kiệm.
class BundleCard extends StatelessWidget {
  const BundleCard({super.key, required this.bundle, this.onTap});

  final Bundle bundle;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Hàng ảnh bìa của các khóa trong combo
              SizedBox(
                height: 64,
                child: Row(
                  children: [
                    for (final course in bundle.courses.take(3)) ...[
                      SizedBox(
                        width: 112,
                        child: AppImage(url: course.thumbnailUrl, aspectRatio: 16 / 9, borderRadius: 8),
                      ),
                      const SizedBox(width: 8),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 10),
              Text(
                bundle.title,
                style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              Text('${bundle.courses.length} khóa học', style: theme.textTheme.bodySmall),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 4,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(
                    formatPrice(bundle.price),
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: theme.colorScheme.primary,
                    ),
                  ),
                  if (bundle.savings > 0) ...[
                    Text(
                      formatPrice(bundle.originalTotal),
                      key: const ValueKey('bundle-original-total'),
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.outline,
                        decoration: TextDecoration.lineThrough,
                      ),
                    ),
                    SmallBadge(text: 'Tiết kiệm ${formatPrice(bundle.savings)}', color: Colors.red.shade700),
                  ],
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
