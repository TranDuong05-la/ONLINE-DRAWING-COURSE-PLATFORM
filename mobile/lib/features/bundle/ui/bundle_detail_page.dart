import 'package:flutter/material.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/utils/format.dart';
import '../../../core/widgets/small_badge.dart';
import '../../../core/widgets/state_views.dart';
import '../../cart/cart_gateway.dart';
import '../../course/ui/course_card.dart';
import '../../course/ui/course_detail_page.dart';
import '../data/bundle_repository.dart';
import '../models/bundle_models.dart';

/// Chi tiết combo: mô tả, giá, tiền tiết kiệm và danh sách khóa học bên trong.
class BundleDetailPage extends StatefulWidget {
  const BundleDetailPage({super.key, required this.bundleId});

  final int bundleId;

  @override
  State<BundleDetailPage> createState() => _BundleDetailPageState();
}

class _BundleDetailPageState extends State<BundleDetailPage> {
  final _repo = BundleRepository();
  Bundle? _bundle;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final bundle = await _repo.detail(widget.bundleId);
      if (!mounted) return;
      setState(() {
        _bundle = bundle;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.displayMessage;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return Scaffold(appBar: AppBar(), body: const LoadingView());
    if (_error != null || _bundle == null) {
      return Scaffold(appBar: AppBar(), body: ErrorView(message: _error ?? 'Có lỗi xảy ra', onRetry: _load));
    }
    final bundle = _bundle!;
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(title: const Text('Combo khóa học')),
      bottomNavigationBar: SafeArea(
        child: Container(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
          decoration: BoxDecoration(
            border: Border(top: BorderSide(color: theme.colorScheme.outlineVariant)),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  formatPrice(bundle.price),
                  style: theme.textTheme.titleLarge?.copyWith(
                    fontWeight: FontWeight.bold,
                    color: theme.colorScheme.primary,
                  ),
                ),
              ),
              FilledButton.icon(
                onPressed: () => cartGateway.addBundle(context, bundleId: bundle.id, title: bundle.title),
                icon: const Icon(Icons.add_shopping_cart),
                label: const Text('Thêm vào giỏ'),
              ),
            ],
          ),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(bundle.title, style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold)),
          if (bundle.description != null && bundle.description!.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(bundle.description!),
          ],
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              if (bundle.savings > 0) ...[
                Text(
                  'Mua lẻ: ${formatPrice(bundle.originalTotal)}',
                  style: TextStyle(
                    color: theme.colorScheme.outline,
                    decoration: TextDecoration.lineThrough,
                  ),
                ),
                SmallBadge(text: 'Tiết kiệm ${formatPrice(bundle.savings)}', color: Colors.red.shade700),
              ],
            ],
          ),
          const SizedBox(height: 20),
          Text('Gồm ${bundle.courses.length} khóa học',
              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          for (final course in bundle.courses) ...[
            CourseCard(
              course: course,
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => CourseDetailPage(courseId: course.id)),
              ),
            ),
            const SizedBox(height: 12),
          ],
        ],
      ),
    );
  }
}
