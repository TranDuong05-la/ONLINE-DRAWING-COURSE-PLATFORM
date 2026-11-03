import 'package:flutter/material.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/utils/format.dart';
import '../../../core/widgets/dialogs.dart';
import '../../../core/widgets/state_views.dart';
import '../../course/models/course_models.dart';
import '../data/course_admin_repository.dart';
import '../models/admin_models.dart';

/// Chọn dụng cụ tặng kèm cho khóa học: tick sản phẩm và chọn số lượng (1-99).
/// Lưu xong pop trả về true.
class AdminKitsPage extends StatefulWidget {
  const AdminKitsPage({super.key, required this.courseId, required this.currentKits});

  final int courseId;
  final List<Kit> currentKits;

  @override
  State<AdminKitsPage> createState() => _AdminKitsPageState();
}

class _AdminKitsPageState extends State<AdminKitsPage> {
  final _repo = CourseAdminRepository();
  List<Product> _products = [];
  final Map<int, int> _selected = {}; // productId -> số lượng
  String? _error;
  bool _loading = true;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    for (final kit in widget.currentKits) {
      _selected[kit.productId] = kit.quantity;
    }
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final products = await _repo.listProducts();
      if (!mounted) return;
      setState(() {
        _products = products;
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

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await _repo.replaceKits(widget.courseId, _selected);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      await showErrorDialog(context, 'Không thể lưu quà tặng', e.displayMessage);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Dụng cụ tặng kèm')),
      bottomNavigationBar: _loading || _error != null
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: FilledButton(
                  onPressed: _saving ? null : _save,
                  child: _saving
                      ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                      : Text('Lưu (${_selected.length} món quà)'),
                ),
              ),
            ),
      body: _loading
          ? const LoadingView()
          : _error != null
              ? ErrorView(message: _error!, onRetry: _load)
              : _products.isEmpty
                  ? const EmptyView(message: 'Chưa có sản phẩm nào để chọn làm quà')
                  : ListView.separated(
                      padding: const EdgeInsets.all(16),
                      itemCount: _products.length,
                      separatorBuilder: (_, _) => const SizedBox(height: 8),
                      itemBuilder: (context, i) => _buildProduct(_products[i]),
                    ),
    );
  }

  Widget _buildProduct(Product product) {
    final quantity = _selected[product.id];
    final selected = quantity != null;
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
        child: Row(
          children: [
            Checkbox(
              value: selected,
              onChanged: (v) => setState(() {
                if (v == true) {
                  _selected[product.id] = 1;
                } else {
                  _selected.remove(product.id);
                }
              }),
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(product.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                  Text('${product.sku} · ${formatPrice(product.price)}',
                      style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
            if (selected) ...[
              IconButton(
                tooltip: 'Giảm',
                icon: const Icon(Icons.remove_circle_outline),
                onPressed: quantity > 1 ? () => setState(() => _selected[product.id] = quantity - 1) : null,
              ),
              Text('$quantity', style: const TextStyle(fontWeight: FontWeight.bold)),
              IconButton(
                tooltip: 'Tăng',
                icon: const Icon(Icons.add_circle_outline),
                onPressed: quantity < 99 ? () => setState(() => _selected[product.id] = quantity + 1) : null,
              ),
            ],
          ],
        ),
      ),
    );
  }
}
