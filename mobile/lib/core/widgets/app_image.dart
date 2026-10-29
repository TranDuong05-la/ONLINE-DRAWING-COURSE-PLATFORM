import 'package:flutter/material.dart';

import '../network/media_url.dart';

/// Ảnh từ backend (đường dẫn tương đối hoặc link ngoài). Chưa có ảnh / lỗi tải thì hiện khung màu thay thế.
class AppImage extends StatelessWidget {
  const AppImage({
    super.key,
    required this.url,
    this.aspectRatio = 16 / 9,
    this.borderRadius = 0,
    this.icon = Icons.palette_outlined,
  });

  final String? url;
  final double aspectRatio;
  final double borderRadius;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final placeholder = Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [scheme.primaryContainer, scheme.secondaryContainer],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: Center(child: Icon(icon, size: 40, color: scheme.primary)),
    );

    final resolved = resolveMediaUrl(url);
    final child = resolved.isEmpty
        ? placeholder
        : Image.network(
            resolved,
            fit: BoxFit.cover,
            errorBuilder: (_, _, _) => placeholder,
            loadingBuilder: (context, child, progress) =>
                progress == null ? child : placeholder,
          );

    return ClipRRect(
      borderRadius: BorderRadius.circular(borderRadius),
      child: AspectRatio(aspectRatio: aspectRatio, child: child),
    );
  }
}
