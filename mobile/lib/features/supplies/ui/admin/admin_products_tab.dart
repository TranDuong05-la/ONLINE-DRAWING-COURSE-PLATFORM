import 'dart:async';

import 'package:flutter/material.dart';

import '../widgets/supply_format.dart';
import '../widgets/supply_states.dart';
import '../../models/supply_models.dart';
import '../../data/supply_repository.dart';
import '../widgets/supply_widgets.dart';
import 'admin_product_form_screen.dart';
import 'admin_product_stock_screen.dart';

class AdminProductsTab extends StatefulWidget {
  /// ADMIN được thêm/sửa/ẩn sản phẩm; STAFF chỉ xem tồn và nhập kho.
  final bool canEdit;
  const AdminProductsTab({super.key, this.canEdit = false});

  @override
  State<AdminProductsTab> createState() => _AdminProductsTabState();
}

class _AdminProductsTabState extends State<AdminProductsTab> with AutomaticKeepAliveClientMixin {
  final _repo = SupplyRepository.instance;
  final _search = TextEditingController();
  Timer? _debounce;

  final List<SupplyProduct> _items = [];
  String? _status; // null | ACTIVE | HIDDEN
  bool _lowStockOnly = false;
  int _page = 0;
  bool _hasMore = true;
  bool _loading = false;
  Object? _error;
  int _requestId = 0;

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _search.dispose();
    super.dispose();
  }

  Future<void> _reload() async {
    _requestId++;
    setState(() {
      _items.clear();
      _page = 0;
      _hasMore = true;
      _error = null;
      _loading = false;
    });
    await _loadMore();
  }

  Future<void> _loadMore() async {
    if (_loading || !_hasMore) return;
    final id = _requestId;
    setState(() => _loading = true);
    try {
      final r = await _repo.managerProducts(
        q: _search.text.trim(),
        active: _status == null ? null : _status == 'ACTIVE',
        lowStockOnly: _lowStockOnly,
        page: _page,
      );
      if (!mounted || id != _requestId) return;
      setState(() {
        _items.addAll(r.content);
        _hasMore = !r.last;
        _page++;
      });
    } catch (e) {
      if (mounted && id == _requestId) setState(() => _error = e);
    } finally {
      if (mounted && id == _requestId) setState(() => _loading = false);
    }
  }

  Future<void> _openForm([SupplyProduct? product]) async {
    final saved = await Navigator.push<bool>(
      context,
      MaterialPageRoute(builder: (_) => AdminProductFormScreen(product: product)),
    );
    if (saved == true) _reload();
  }

  Future<void> _openStock(SupplyProduct p) async {
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => AdminProductStockScreen(productId: p.id, canEdit: widget.canEdit)),
    );
    _reload();
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Scaffold(
      floatingActionButton: widget.canEdit
          ? FloatingActionButton.extended(
              heroTag: 'add-product',
              onPressed: () => _openForm(),
              icon: const Icon(Icons.add),
              label: const Text('Thêm học cụ'),
            )
          : null,
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: TextField(
              controller: _search,
              decoration: const InputDecoration(
                prefixIcon: Icon(Icons.search),
                hintText: 'Tìm tên hoặc SKU',
                border: OutlineInputBorder(),
                isDense: true,
              ),
              onChanged: (_) {
                _debounce?.cancel();
                _debounce = Timer(const Duration(milliseconds: 400), _reload);
              },
            ),
          ),
          SizedBox(
            height: 48,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              children: [
                for (final (value, label) in const [
                  (null, 'Tất cả'),
                  ('ACTIVE', 'Đang bán'),
                  ('HIDDEN', 'Đã ẩn'),
                ])
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      label: Text(label),
                      selected: _status == value,
                      onSelected: (_) {
                        setState(() => _status = value);
                        _reload();
                      },
                    ),
                  ),
                FilterChip(
                  avatar: const Icon(Icons.warning_amber_rounded, size: 18),
                  label: const Text('Sắp hết / hết hàng'),
                  selected: _lowStockOnly,
                  onSelected: (v) {
                    setState(() => _lowStockOnly = v);
                    _reload();
                  },
                ),
              ],
            ),
          ),
          Expanded(child: _buildList()),
        ],
      ),
    );
  }

  Widget _buildList() {
    if (_items.isEmpty) {
      if (_loading) return const LoadingView();
      if (_error != null) return ErrorView(error: _error!, onRetry: _reload);
      return const EmptyView(message: 'Không có học cụ nào', icon: Icons.inventory_2_outlined);
    }
    return RefreshIndicator(
      onRefresh: _reload,
      child: NotificationListener<ScrollNotification>(
        onNotification: (n) {
          if (n.metrics.pixels > n.metrics.maxScrollExtent - 200) _loadMore();
          return false;
        },
        child: ListView.separated(
          padding: const EdgeInsets.only(bottom: 96),
          itemCount: _items.length + (_hasMore ? 1 : 0),
          separatorBuilder: (_, _) => const Divider(height: 1),
          itemBuilder: (context, i) {
            if (i >= _items.length) {
              return const Padding(padding: EdgeInsets.all(16), child: LoadingView());
            }
            final p = _items[i];
            return ListTile(
              onTap: () => _openStock(p),
              leading: SupplyImage(url: p.thumbnailUrl, size: 52),
              title: Text(p.name, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('${p.sku} · ${formatVnd(p.price)}'),
                  Text('Kho ${p.stockOnHand} · Đang giữ ${p.stockReserved} · Còn bán ${p.available}'),
                ],
              ),
              isThreeLine: true,
              trailing: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  if (p.hidden)
                    const Text('Đã ẩn', style: TextStyle(color: Colors.grey))
                  else
                    StockBadge(available: p.available, lowStock: p.lowStock),
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}
