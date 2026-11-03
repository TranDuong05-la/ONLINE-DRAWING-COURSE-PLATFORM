import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import '../../core/constants/app_colors.dart';
import '../../core/network/media_url.dart';

/// Loads data once and shows a spinner, an error with "Thử lại", or the content.
/// Change its `key` to force a reload from outside.
class AsyncBody<T> extends StatefulWidget {
  const AsyncBody({super.key, required this.load, required this.builder});

  final Future<T> Function() load;
  final Widget Function(BuildContext context, T data, Future<void> Function() reload) builder;

  @override
  State<AsyncBody<T>> createState() => _AsyncBodyState<T>();
}

class _AsyncBodyState<T> extends State<AsyncBody<T>> {
  late Future<T> _future = widget.load();

  Future<void> _reload() async {
    final next = widget.load();
    setState(() => _future = next);
    try {
      await next;
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<T>(
      future: _future,
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const Center(
            child: CircularProgressIndicator(valueColor: AlwaysStoppedAnimation(AppColors.primary)),
          );
        }
        if (snap.hasError) return ErrorRetry(message: snap.error.toString(), onRetry: _reload);
        return widget.builder(context, snap.data as T, _reload);
      },
    );
  }
}

class ErrorRetry extends StatelessWidget {
  const ErrorRetry({super.key, required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, size: 48, color: AppColors.error),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center, style: GoogleFonts.plusJakartaSans(fontSize: 14)),
            const SizedBox(height: 16),
            ElevatedButton(onPressed: onRetry, child: const Text('Thử lại')),
          ],
        ),
      ),
    );
  }
}

/// Small rounded label, e.g. "Đạt", "Cần nộp lại".
class StatusPill extends StatelessWidget {
  const StatusPill({super.key, required this.label, required this.color, this.icon});

  final String label;
  final Color color;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[Icon(icon, size: 14, color: color), const SizedBox(width: 4)],
          Text(label,
              style: GoogleFonts.plusJakartaSans(fontSize: 12, fontWeight: FontWeight.w600, color: color)),
        ],
      ),
    );
  }
}

Color assignmentStatusColor(String? status) => switch (status) {
      'APPROVED' => AppColors.success,
      'NEEDS_REVISION' => AppColors.warning,
      'PENDING' => AppColors.info,
      _ => AppColors.textSecondary,
    };

/// Course cover: the thumbnail when there is one, otherwise a pastel tile with a brush icon.
class CourseCover extends StatelessWidget {
  const CourseCover({super.key, required this.seed, this.thumbnailUrl, this.size = 72, this.radius = 14});

  final int seed;
  final String? thumbnailUrl;
  final double size;
  final double radius;

  static const _palette = [
    (AppColors.pastelPeach, AppColors.accentPeach, Icons.water_drop),
    (AppColors.pastelLavender, Color(0xFF8E7CC3), Icons.brush),
    (AppColors.pastelTeal, AppColors.primaryMedium, Icons.palette),
    (AppColors.pastelGreen, AppColors.primary, Icons.draw),
    (AppColors.accentYellowLight, AppColors.accentYellow, Icons.auto_awesome),
  ];

  @override
  Widget build(BuildContext context) {
    final (bg, fg, icon) = _palette[seed % _palette.length];
    final fallback = Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(radius)),
      child: Icon(icon, color: fg, size: size * 0.42),
    );
    final url = resolveMediaUrl(thumbnailUrl);
    if (url.isEmpty) return fallback;
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: Image.network(url, width: size, height: size, fit: BoxFit.cover,
          errorBuilder: (_, _, _) => fallback),
    );
  }
}

void showSnack(BuildContext context, String message, {bool isError = false}) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(
      content: Text(message),
      backgroundColor: isError ? AppColors.error : AppColors.primary,
    ));
}

String formatVnd(double price) {
  final s = price.round().toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (_) => '.');
  return '$sđ';
}
