import 'dart:async';

import 'package:flutter/material.dart';

import 'widgets/supply_states.dart';
import '../models/supply_models.dart';
import '../data/supply_repository.dart';
import 'my_shipments_screen.dart';
import 'product_detail_screen.dart';
import 'widgets/supply_widgets.dart';

/// Tab "Học cụ": danh sách, tìm kiếm, lọc.
class SupplyShopScreen extends StatefulWidget {
  const SupplyShopScreen({super.key});

  @override
  State<SupplyShopScreen> createState() => _SupplyShopScreenState();
}

class _SupplyShopScreenState extends State<SupplyShopScreen> {
  final _repo = SupplyRepository.instance;
  final _searchCtrl = TextEditingController();
  final _scrollCtrl = ScrollController();
  Timer? _debounce;

  List<SupplyCategory> _categories = [];
  final List<SupplyProduct> _items = [];
  int? _categoryId;
  bool _inStockOnly = false;
  String _sort = 'newest';
  int _page = 0;
  bool _hasMore = true;
  bool _loading = false;
  Object? _error;
  int _requestId = 0;

  static const _sorts = {
    'newest': 'Mới nhất',
    'price_asc': 'Giá tăng dần',
    'price_desc': 'Giá giảm dần',
    'rating': 'Đánh giá cao',
    'name': 'Tên A-Z',
  };

  @override
  void initState() {
    super.initState();
    _scrollCtrl.addListener(() {
      if (_scrollCtrl.position.pixels > _scrollCtrl.position.maxScrollExtent - 300) _loadMore();
    });
    _loadCategories();
    _reload();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchCtrl.dispose();
    _scrollCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadCategories() async {
    try {
      final c = await _repo.categories();
      if (mounted) setState(() => _categories = c);
    } catch (_) {
      // Danh mục lỗi không chặn danh sách sản phẩm
    }
  }

  Future<void> _reload() async {
    setState(() {
      _items.clear();
      _page = 0;
      _hasMore = true;
      _error = null;
    });
    await _loadMore();
  }

  Future<void> _loadMore() async {
    if (_loading || !_hasMore) return;
    final requestId = ++_requestId;
    setState(() => _loading = true);
    try {
      final result = await _repo.products(
        q: _searchCtrl.text.trim(),
        categoryId: _categoryId,
        inStockOnly: _inStockOnly,
        sort: _sort,
        page: _page,
      );
      if (!mounted || requestId != _requestId) return;
      setState(() {
        _items.addAll(result.content);
        _hasMore = !result.last;
        _page++;
      });
    } catch (e) {
      if (mounted && requestId == _requestId) setState(() => _error = e);
    } finally {
      if (mounted && requestId == _requestId) setState(() => _loading = false);
    }
  }

  void _onSearchChanged(String _) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () {
      _requestId++; // bỏ kết quả của lần tìm cũ
      _loading = false;
      _reload();
    });
  }

  void _openMyShipments() {
    Navigator.push(context, MaterialPageRoute(builder: (_) => const MyShipmentsScreen()));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Dụng cụ & Họa cụ'),
        actions: [
          IconButton(
            tooltip: 'Đơn giao hàng của tôi',
            onPressed: _openMyShipments,
            icon: const Icon(Icons.local_shipping_outlined),
          ),
          PopupMenuButton<String>(
            tooltip: 'Sắp xếp',
            icon: const Icon(Icons.sort),
            initialValue: _sort,
            onSelected: (v) {
              setState(() => _sort = v);
              _reload();
            },
            itemBuilder: (_) => [
              for (final e in _sorts.entries) PopupMenuItem(value: e.key, child: Text(e.value)),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
            child: SearchBar(
              controller: _searchCtrl,
              hintText: 'Tìm theo tên hoặc SKU',
              leading: const Icon(Icons.search),
              elevation: const WidgetStatePropertyAll(0),
              onChanged: _onSearchChanged,
              trailing: [
                if (_searchCtrl.text.isNotEmpty)
                  IconButton(
                    icon: const Icon(Icons.clear),
                    onPressed: () {
                      _searchCtrl.clear();
                      _onSearchChanged('');
                    },
                  ),
              ],
            ),
          ),
          SizedBox(
            height: 44,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              children: [
                FilterChip(
                  label: const Text('Còn hàng'),
                  selected: _inStockOnly,
                  onSelected: (v) {
                    setState(() => _inStockOnly = v);
                    _reload();
                  },
                ),
                const SizedBox(width: 8),
                ChoiceChip(
                  label: const Text('Tất cả'),
                  selected: _categoryId == null,
                  onSelected: (_) {
                    setState(() => _categoryId = null);
                    _reload();
                  },
                ),
                for (final c in _categories) ...[
                  const SizedBox(width: 8),
                  ChoiceChip(
                    label: Text(c.name),
                    selected: _categoryId == c.id,
                    onSelected: (_) {
                      setState(() => _categoryId = c.id);
                      _reload();
                    },
                  ),
                ],
              ],
            ),
          ),
          Expanded(child: _buildBody()),
        ],
      ),
    );
  }

  Widget _buildBody() {
    if (_items.isEmpty) {
      if (_loading) return const LoadingView();
      if (_error != null) return ErrorView(error: _error!, onRetry: _reload);
      return RefreshIndicator(
        onRefresh: _reload,
        child: ListView(
          children: const [
            SizedBox(height: 120),
            EmptyView(message: 'Không tìm thấy học cụ phù hợp', icon: Icons.search_off),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _reload,
      child: GridView.builder(
        controller: _scrollCtrl,
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          mainAxisSpacing: 12,
          crossAxisSpacing: 12,
          childAspectRatio: 0.62,
        ),
        itemCount: _items.length + (_hasMore ? 1 : 0),
        itemBuilder: (context, i) {
          if (i >= _items.length) {
            return _error != null
                ? Center(
                    child: TextButton(onPressed: _loadMore, child: const Text('Tải thêm')),
                  )
                : const Center(child: CircularProgressIndicator());
          }
          final p = _items[i];
          return ProductCard(
            product: p,
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => ProductDetailScreen(productId: p.id)),
            ),
          );
        },
      ),
    );
  }
}
