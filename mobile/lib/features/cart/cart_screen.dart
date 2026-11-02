import 'package:flutter/material.dart';

import 'cart_item.dart';
import 'cart_repository.dart';

class CartScreen extends StatefulWidget {
  const CartScreen({super.key, required this.repository});
  final CartRepository repository;

  @override
  State<CartScreen> createState() => _CartScreenState();
}

class _CartScreenState extends State<CartScreen> {
  List<CartItem> _items = [];
  bool _busy = true;
  bool _loaded = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _run(widget.repository.load);
  }

  Future<void> _run(Future<List<CartItem>> Function() operation) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final items = await operation();
      if (!mounted) return;
      setState(() {
        _items = items;
        _loaded = true;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _error = 'Could not update your cart. Please try again.';
      });
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
        });
      }
    }
  }

  Future<void> _confirmClear() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Clear your cart?'),
        content: const Text('All courses and supplies will be removed.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Keep items'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Clear cart'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) await _run(widget.repository.clear);
  }

  @override
  Widget build(BuildContext context) {
    final total = _items.fold<int>(0, (sum, item) => sum + item.subtotalVnd);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Your cart'),
        actions: [
          TextButton(
            onPressed: _busy || _items.isEmpty ? null : _confirmClear,
            child: const Text('Clear all'),
          ),
        ],
      ),
      body: SafeArea(
        child: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 1000),
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Card(
                  key: const ValueKey('cart-demo-banner'),
                  color: Theme.of(context).colorScheme.secondaryContainer,
                  child: const Padding(
                    padding: EdgeInsets.all(12),
                    child: Row(
                      children: [
                        Icon(Icons.science_outlined),
                        SizedBox(width: 8),
                        Flexible(child: Text('Demo cart · Sample data')),
                      ],
                    ),
                  ),
                ),
                if (_busy) const LinearProgressIndicator(),
                if (_error != null)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Semantics(liveRegion: true, child: Text(_error!)),
                          TextButton(
                            onPressed: _busy
                                ? null
                                : () => _run(widget.repository.load),
                            child: const Text('Reload cart'),
                          ),
                        ],
                      ),
                    ),
                  ),
                if (_loaded && _items.isEmpty) _emptyState(context),
                if (_items.isNotEmpty) ...[
                  Text(
                    'Make time to create',
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 8),
                  Text('${_items.length} items · Courses and art supplies'),
                  const SizedBox(height: 16),
                  LayoutBuilder(
                    builder: (context, constraints) {
                      final list = Column(
                        children: [
                          for (final item in _items) _itemCard(context, item),
                        ],
                      );
                      final summary = _summary(context, total);
                      if (constraints.maxWidth >= 760) {
                        return Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Expanded(child: list),
                            const SizedBox(width: 24),
                            SizedBox(width: 280, child: summary),
                          ],
                        );
                      }
                      return Column(
                        children: [list, const SizedBox(height: 16), summary],
                      );
                    },
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _emptyState(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 64, horizontal: 16),
    child: Column(
      children: [
        Icon(
          Icons.shopping_bag_outlined,
          size: 72,
          color: Theme.of(context).colorScheme.primary,
        ),
        const SizedBox(height: 24),
        Text(
          'Your cart is empty',
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 8),
        const Text(
          'Courses and supplies you add will appear here.',
          textAlign: TextAlign.center,
        ),
      ],
    ),
  );

  Widget _itemCard(BuildContext context, CartItem item) {
    final course = item.type == CartItemType.course;
    final colors = Theme.of(context).colorScheme;
    return Card(
      key: ValueKey('item-${item.id}'),
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Semantics(
                  label: '${course ? 'Course' : 'Product'} image placeholder',
                  child: Container(
                    width: 56,
                    height: 64,
                    decoration: BoxDecoration(
                      color: colors.primaryContainer,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Icon(
                      course ? Icons.school_outlined : Icons.brush_outlined,
                      color: colors.onPrimaryContainer,
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        course ? 'COURSE' : 'PRODUCT',
                        style: Theme.of(context).textTheme.labelSmall
                            ?.copyWith(color: colors.primary),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        item.name,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text('${formatVnd(item.unitPriceVnd)} / item'),
                    ],
                  ),
                ),
                IconButton(
                  tooltip: 'Remove ${item.name}',
                  onPressed: _busy
                      ? null
                      : () => _run(() => widget.repository.remove(item.id)),
                  icon: const Icon(Icons.close),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 16,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                if (course)
                  const Text('Quantity: 1 · Individual access')
                else
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        key: ValueKey('decrease-${item.id}'),
                        tooltip: 'Decrease ${item.name} quantity',
                        onPressed: _busy || item.quantity <= 1
                            ? null
                            : () => _run(
                                () => widget.repository.setQuantity(
                                  item.id,
                                  item.quantity - 1,
                                ),
                              ),
                        icon: const Icon(Icons.remove),
                      ),
                      Semantics(
                        label: '${item.name} quantity',
                        child: Text(
                          '${item.quantity}',
                          key: ValueKey('quantity-${item.id}'),
                        ),
                      ),
                      IconButton(
                        key: ValueKey('increase-${item.id}'),
                        tooltip: 'Increase ${item.name} quantity',
                        onPressed: _busy
                            ? null
                            : () => _run(
                                () => widget.repository.setQuantity(
                                  item.id,
                                  item.quantity + 1,
                                ),
                              ),
                        icon: const Icon(Icons.add),
                      ),
                    ],
                  ),
                Text(
                  'Subtotal: ${formatVnd(item.subtotalVnd)}',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _summary(BuildContext context, int total) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Cart summary', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 16),
          const Text('Cart subtotal'),
          Text(
            formatVnd(total),
            key: const ValueKey('cart-total'),
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text('Shipping and any discounts are not included.'),
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: _busy
                ? null
                : () => showDialog<void>(
                    context: context,
                    builder: (context) => AlertDialog(
                      title: const Text('Checkout is not available yet'),
                      content: const Text(
                        'Ordering and payment will be available after integration. No order has been placed.',
                      ),
                      actions: [
                        TextButton(
                          onPressed: () => Navigator.pop(context),
                          child: const Text('Back to cart'),
                        ),
                      ],
                    ),
                  ),
            icon: const Icon(Icons.arrow_forward),
            label: const Text('Checkout'),
          ),
        ],
      ),
    ),
  );
}
