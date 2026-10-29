import 'package:flutter/material.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/widgets/state_views.dart';
import '../data/bundle_repository.dart';
import '../models/bundle_models.dart';
import 'bundle_card.dart';
import 'bundle_detail_page.dart';

/// Tab "Combo" cho học viên.
class BundleListPage extends StatefulWidget {
  const BundleListPage({super.key});

  @override
  State<BundleListPage> createState() => _BundleListPageState();
}

class _BundleListPageState extends State<BundleListPage> with AutomaticKeepAliveClientMixin {
  final _repo = BundleRepository();
  List<Bundle>? _bundles;
  String? _error;
  bool _loading = true;

  @override
  bool get wantKeepAlive => true;

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
      final bundles = await _repo.list();
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

  @override
  Widget build(BuildContext context) {
    super.build(context);
    if (_loading && _bundles == null) return const LoadingView();
    if (_error != null && _bundles == null) return ErrorView(message: _error!, onRetry: _load);

    final bundles = _bundles ?? [];
    return RefreshIndicator(
      onRefresh: _load,
      child: bundles.isEmpty
          ? LayoutBuilder(
              builder: (context, c) => ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                children: [
                  SizedBox(height: c.maxHeight, child: const EmptyView(message: 'Chưa có combo nào')),
                ],
              ),
            )
          : ListView.separated(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              itemCount: bundles.length,
              separatorBuilder: (_, _) => const SizedBox(height: 12),
              itemBuilder: (context, i) => BundleCard(
                bundle: bundles[i],
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => BundleDetailPage(bundleId: bundles[i].id)),
                ),
              ),
            ),
    );
  }
}
