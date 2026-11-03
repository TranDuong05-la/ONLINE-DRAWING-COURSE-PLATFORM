import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../widgets/supply_format.dart';
import '../widgets/supply_states.dart';
import '../../models/supply_models.dart';
import '../../data/supply_repository.dart';
import '../widgets/supply_widgets.dart';
import 'admin_product_form_screen.dart';

/// Xem tồn kho, nhập hàng và lịch sử kho của một học cụ (STAFF/ADMIN). ADMIN thêm được sửa/ẩn.
class AdminProductStockScreen extends StatefulWidget {
  final int productId;
  final bool canEdit;
  const AdminProductStockScreen({super.key, required this.productId, this.canEdit = false});

  @override
  State<AdminProductStockScreen> createState() => _AdminProductStockScreenState();
}

class _AdminProductStockScreenState extends State<AdminProductStockScreen> {
  final _repo = SupplyRepository.instance;
  SupplyProduct? _product;
  Object? _error;
  final List<InventoryTx> _txs = [];
  int _txPage = 0;
  bool _txHasMore = false;
  bool _txLoading = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final p = await _repo.managerProduct(widget.productId);
      if (!mounted) return;
      setState(() => _product = p);
      await _loadTxs(reset: true);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _loadTxs({bool reset = false}) async {
    if (_txLoading) return;
    setState(() => _txLoading = true);
    try {
      final page = reset ? 0 : _txPage;
      final r = await _repo.inventoryTransactions(widget.productId, page: page);
      if (!mounted) return;
      setState(() {
        if (reset) _txs.clear();
        _txs.addAll(r.content);
        _txPage = page + 1;
        _txHasMore = !r.last;
      });
    } catch (e) {
      if (mounted) showMessage(context, e.toString());
    } finally {
      if (mounted) setState(() => _txLoading = false);
    }
  }

  Future<void> _import() async {
    final result = await showDialog<(int, String)>(context: context, builder: (_) => const _ImportDialog());
    if (result == null) return;
    try {
      final p = await _repo.importStock(widget.productId, result.$1, result.$2);
      if (!mounted) return;
      setState(() => _product = p);
      showMessage(context, 'Đã nhập ${result.$1} vào kho');
      _loadTxs(reset: true);
    } catch (e) {
      if (mounted) showMessage(context, e.toString());
    }
  }

  Future<void> _toggleHidden() async {
    final p = _product!;
    try {
      final updated = await _repo.setProductActive(p.id, p.hidden);
      if (!mounted) return;
      setState(() => _product = updated);
      showMessage(context, updated.hidden ? 'Đã ẩn khỏi cửa hàng' : 'Đã hiện lại trên cửa hàng');
    } catch (e) {
      if (mounted) showMessage(context, e.toString());
    }
  }

  Future<void> _edit() async {
    final saved = await Navigator.push<bool>(
      context,
      MaterialPageRoute(builder: (_) => AdminProductFormScreen(product: _product)),
    );
    if (saved == true) _load();
  }

  @override
  Widget build(BuildContext context) {
    final p = _product;
    return Scaffold(
      appBar: AppBar(
        title: Text(p?.name ?? 'Kho học cụ'),
        actions: [
          if (p != null && widget.canEdit) ...[
            IconButton(tooltip: 'Sửa', onPressed: _edit, icon: const Icon(Icons.edit_outlined)),
            IconButton(
              tooltip: p.hidden ? 'Hiện lại' : 'Ẩn khỏi cửa hàng',
              onPressed: _toggleHidden,
              icon: Icon(p.hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined),
            ),
          ],
        ],
      ),
      floatingActionButton: p == null
          ? null
          : FloatingActionButton.extended(
              onPressed: _import,
              icon: const Icon(Icons.add_box_outlined),
              label: const Text('Nhập kho'),
            ),
      body: p == null
          ? (_error != null ? ErrorView(error: _error!, onRetry: _load) : const LoadingView())
          : RefreshIndicator(onRefresh: _load, child: _buildContent(p)),
    );
  }

  Widget _buildContent(SupplyProduct p) {
    final text = Theme.of(context).textTheme;
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
      children: [
        Row(
          children: [
            SupplyImage(url: p.thumbnailUrl, size: 72),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(p.name, style: text.titleMedium),
                  Text('${p.sku} · ${p.categoryName ?? 'Chưa có danh mục'}', style: text.bodySmall),
                  Text(formatVnd(p.price)),
                  if (p.hidden) const Text('Đang ẩn khỏi cửa hàng', style: TextStyle(color: Colors.grey)),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            _StockTile(label: 'Trong kho', value: p.stockOnHand ?? 0, icon: Icons.warehouse_outlined),
            const SizedBox(width: 8),
            _StockTile(label: 'Đang giữ', value: p.stockReserved ?? 0, icon: Icons.lock_clock_outlined),
            const SizedBox(width: 8),
            _StockTile(label: 'Còn bán', value: p.available, icon: Icons.sell_outlined, warn: p.lowStock),
          ],
        ),
        if (p.lowStock)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              p.available == 0
                  ? 'Hết hàng có thể bán. Nhập thêm để bán tiếp.'
                  : 'Sắp hết hàng (ngưỡng cảnh báo ${p.lowStockThreshold}).',
              style: TextStyle(color: Colors.orange.shade800),
            ),
          ),
        const SizedBox(height: 4),
        Text('"Đang giữ" là hàng của đơn đã đặt nhưng chưa xuất kho.', style: text.bodySmall),
        const Divider(height: 32),
        Text('Lịch sử kho', style: text.titleMedium),
        const SizedBox(height: 4),
        if (_txs.isEmpty && !_txLoading)
          const Padding(padding: EdgeInsets.all(16), child: Text('Chưa có giao dịch kho.')),
        for (final t in _txs) _TxTile(tx: t),
        if (_txLoading) const Padding(padding: EdgeInsets.all(12), child: LoadingView()),
        if (_txHasMore && !_txLoading)
          Center(
            child: TextButton(onPressed: () => _loadTxs(), child: const Text('Xem thêm')),
          ),
      ],
    );
  }
}

class _StockTile extends StatelessWidget {
  final String label;
  final int value;
  final IconData icon;
  final bool warn;
  const _StockTile({required this.label, required this.value, required this.icon, this.warn = false});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = warn ? Colors.orange.shade800 : scheme.onSurface;
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: warn ? Colors.orange.withValues(alpha: 0.1) : scheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          children: [
            Icon(icon, size: 20, color: color),
            const SizedBox(height: 4),
            Text(
              '$value',
              style: Theme.of(context).textTheme.headlineSmall
                  ?.copyWith(color: color, fontWeight: FontWeight.bold),
            ),
            Text(label, style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
      ),
    );
  }
}

class _TxTile extends StatelessWidget {
  final InventoryTx tx;
  const _TxTile({required this.tx});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final change = switch (tx.type) {
      'IMPORT' || 'RETURN' => '+${tx.quantity} kho',
      'EXPORT' => '-${tx.quantity} kho',
      'RESERVE' => '+${tx.quantity} giữ',
      'RELEASE' => '-${tx.quantity} giữ',
      _ => '${tx.quantity}',
    };
    final color = switch (tx.type) {
      'IMPORT' || 'RETURN' => Colors.green.shade700,
      'EXPORT' => Colors.red.shade700,
      _ => Colors.blueGrey.shade600,
    };
    final refs = [
      if (tx.orderId != null) 'Đơn #${tx.orderId}',
      if (tx.shipmentId != null) 'Giao #${tx.shipmentId}',
      if (tx.gift) 'Quà',
      if (tx.note != null) tx.note!,
    ].join(' · ');
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(tx.icon, color: color),
      title: Row(
        children: [
          Text(tx.label),
          const Spacer(),
          Text(
            change,
            style: TextStyle(color: color, fontWeight: FontWeight.w600),
          ),
        ],
      ),
      subtitle: Text(
        [
          formatDateTime(tx.createdAt),
          'Sau: kho ${tx.onHandAfter}, giữ ${tx.reservedAfter}',
          if (refs.isNotEmpty) refs,
        ].join('\n'),
        style: text.bodySmall,
      ),
      isThreeLine: true,
    );
  }
}

class _ImportDialog extends StatefulWidget {
  const _ImportDialog();

  @override
  State<_ImportDialog> createState() => _ImportDialogState();
}

class _ImportDialogState extends State<_ImportDialog> {
  final _qty = TextEditingController();
  final _note = TextEditingController();
  final _form = GlobalKey<FormState>();

  @override
  void dispose() {
    _qty.dispose();
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Nhập kho'),
      content: Form(
        key: _form,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextFormField(
              controller: _qty,
              autofocus: true,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: const InputDecoration(labelText: 'Số lượng nhập'),
              validator: (v) {
                final n = int.tryParse(v ?? '');
                if (n == null || n <= 0) return 'Nhập số lớn hơn 0';
                if (n > 100000) return 'Tối đa 100000 mỗi lần';
                return null;
              },
            ),
            TextFormField(
              controller: _note,
              decoration: const InputDecoration(labelText: 'Ghi chú (nhà cung cấp, lô...)'),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Hủy')),
        FilledButton(
          onPressed: () {
            if (_form.currentState!.validate()) {
              Navigator.pop(context, (int.parse(_qty.text), _note.text.trim()));
            }
          },
          child: const Text('Nhập'),
        ),
      ],
    );
  }
}
