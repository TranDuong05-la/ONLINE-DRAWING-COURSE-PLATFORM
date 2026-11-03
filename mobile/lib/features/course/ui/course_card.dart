import 'package:flutter/material.dart';

import '../../../core/utils/format.dart';
import '../../../core/widgets/app_image.dart';
import '../../../core/widgets/price_text.dart';
import '../../../core/widgets/small_badge.dart';
import '../models/course_models.dart';

/// Thẻ khóa học ở danh sách. Dùng cho cả màn học viên và (có thêm [showStatus]) màn admin.
class CourseCard extends StatelessWidget {
  const CourseCard({
    super.key,
    required this.course,
    this.onTap,
    this.showStatus = false,
  });

  final CourseSummary course;
  final VoidCallback? onTap;

  /// Admin cần thấy trạng thái Nháp / Đang bán / Ngừng bán.
  final bool showStatus;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            AppImage(url: course.thumbnailUrl),
            Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    course.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                  ),
                  if (course.shortDescription != null && course.shortDescription!.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                      course.shortDescription!,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.outline),
                    ),
                  ],
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      if (showStatus) _statusBadge(course.status),
                      SmallBadge(
                        text: levelLabel(course.level),
                        color: course.level == 'ADVANCED' ? Colors.deepPurple : theme.colorScheme.primary,
                      ),
                      Text(
                        '${course.lessonCount} bài · ${formatDuration(course.totalDurationSeconds)}',
                        style: theme.textTheme.bodySmall,
                      ),
                      if (course.hasKits)
                        const SmallBadge(
                          text: 'Tặng kèm dụng cụ',
                          color: Color(0xFFE65100),
                          icon: Icons.card_giftcard,
                        ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  PriceText(price: course.price, discountPrice: course.discountPrice),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statusBadge(String status) {
    final color = switch (status) {
      'PUBLISHED' => Colors.green.shade700,
      'HIDDEN' => Colors.grey.shade700,
      _ => Colors.amber.shade800,
    };
    return SmallBadge(text: statusLabel(status), color: color);
  }
}
