import 'package:flutter/material.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/utils/format.dart';
import '../../../core/widgets/small_badge.dart';
import '../../../core/widgets/state_views.dart';
import '../../bundle/models/bundle_models.dart';
import '../data/course_admin_repository.dart';
import 'admin_bundle_form_page.dart';

/// Quản lý combo: xem tất cả, bật/tắt, thêm, sửa. (Không xóa, chỉ tắt.)
class AdminBundleListPage extends StatefulWidget {
  const AdminBundleListPage({super.key});

  @override
  State<AdminBundleListPage> createState() => _AdminBundleListPageState();
}

class _AdminBundleListPageState extends State<AdminBundleListPage> with AutomaticKeepAliveClientMixin {
  final _repo = CourseAdminRepository();
  List<AdminBundle>? _bundles;
  String? _error;
  bool _loading = true;

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool silent = false}) async {
    if (!silent) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final bundles = await _repo.listBundles();
      if (!mounted) return;
      setState(() {
        _bundles = bundles;
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

  Future<void> _toggleActive(AdminBundle bundle, bool active) async {
    try {
      await _repo.setBundleActive(bundle.id, active);
      if (mounted) _load(silent: true);
    } on ApiException catch (e) {
      if (mounted) showMessage(context, e.displayMessage, error: true);
    }
  }

  Future<void> _openForm([AdminBundle? bundle]) async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => AdminBundleFormPage(bundle: bundle)),
    );
    if (saved == true && mounted) _load(silent: true);
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        // heroTag null: các tab/màn đều có nút này cùng lúc, trùng tag mặc định sẽ báo lỗi khi chuyển trang
        heroTag: null,
        onPressed: () => _openForm(),
        icon: const Icon(Icons.add),
        label: const Text('Thêm combo'),
      ),
      body: _body(),
    );
  }

  Widget _body() {
    if (_loading && _bundles == null) return const LoadingView();
    if (_error != null && _bundles == null) return ErrorView(message: _error!, onRetry: _load);
    final bundles = _bundles ?? [];
    return RefreshIndicator(
      onRefresh: () => _load(silent: true),
      child: bundles.isEmpty
          ? LayoutBuilder(
              builder: (context, c) => ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                children: [SizedBox(height: c.maxHeight, child: const EmptyView(message: 'Chưa có combo nào'))],
              ),
            )
          : ListView.separated(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 88),
              itemCount: bundles.length,
              separatorBuilder: (_, _) => const SizedBox(height: 12),
              itemBuilder: (context, i) => _buildCard(bundles[i]),
            ),
    );
  }

  Widget _buildCard(AdminBundle bundle) {
    final theme = Theme.of(context);
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => _openForm(bundle),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(bundle.title,
                        style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                  ),
                  Switch(value: bundle.active, onChanged: (v) => _toggleActive(bundle, v)),
                ],
              ),
              Text('Mã ${bundle.code} · ${bundle.courses.length} khóa học', style: theme.textTheme.bodySmall),
              const SizedBox(height: 6),
              Wrap(
                spacing: 8,
                runSpacing: 4,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(formatPrice(bundle.price),
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: theme.colorScheme.primary,
                      )),
                  Text('Mua lẻ ${formatPrice(bundle.originalTotal)}',
                      style: theme.textTheme.bodySmall?.copyWith(decoration: TextDecoration.lineThrough)),
                  bundle.visibleToPublic
                      ? SmallBadge(text: 'Đang hiện với học viên', color: Colors.green.shade700)
                      : SmallBadge(text: 'Đang ẩn với học viên', color: Colors.grey.shade700),
                ],
              ),
              for (final w in bundle.warnings)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(Icons.warning_amber_rounded, size: 16, color: Colors.amber.shade800),
                      const SizedBox(width: 4),
                      Expanded(child: Text(w, style: theme.textTheme.bodySmall)),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
