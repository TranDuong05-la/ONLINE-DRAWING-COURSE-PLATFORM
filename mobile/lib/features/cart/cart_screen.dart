import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/network/api_exceptions.dart';
import 'cart_item.dart';
import 'cart_repository.dart';
import 'course_purchase_config.dart';

class CartScreen extends StatefulWidget {
  const CartScreen({super.key, required this.repository, this.purchaseEnabled});
  final CartRepository repository;
  final bool? purchaseEnabled;

  @override
  State<CartScreen> createState() => _CartScreenState();
}

class _CartScreenState extends State<CartScreen> {
  CartSnapshot? _cart;
  bool _busy = false;
  String? _error;
  bool get _enabled => widget.purchaseEnabled ?? coursePurchaseUiEnabled;

  @override
  void initState() {
    super.initState();
    if (_enabled) _run(widget.repository.load);
  }

  Future<void> _run(Future<CartSnapshot> Function() operation) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final result = await operation();
      if (mounted) {
        setState(() => _cart = result);
      }
    } catch (error) {
      if (mounted) {
        setState(() => _error = _friendlyError(error));
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  String _friendlyError(Object error) {
    if (error is ConflictException) {
      return 'Your cart changed or this Course is no longer eligible. Reload and try again.';
    }
    if (error is UnauthorizedException) {
      return 'Please sign in again to use your cart.';
    }
    if (error is NetworkException) {
      return 'Could not reach the server. Check your connection and retry.';
    }
    if (error is ApiException && error.message.isNotEmpty) return error.message;
    return 'Could not load your cart. Please try again.';
  }

  Future<void> _confirmClear() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Clear your cart?'),
        content: const Text('All Course items will be removed.'),
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
    if (confirmed == true && mounted) {
      await _run(widget.repository.clear);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_enabled) {
      return Scaffold(
        appBar: AppBar(title: const Text('Your Cart')),
        body: const Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: Text(
              'Course purchasing is disabled in this app build. Enable COURSE_PURCHASE_ENABLED only for an explicitly configured demo or test.',
              textAlign: TextAlign.center,
            ),
          ),
        ),
      );
    }
    final cart = _cart;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Your Cart'),
        actions: [
          IconButton(
            tooltip: 'Reload cart',
            onPressed: _busy ? null : () => _run(widget.repository.load),
            icon: const Icon(Icons.refresh),
          ),
          TextButton(
            onPressed: _busy || cart == null || cart.items.isEmpty
                ? null
                : _confirmClear,
            child: const Text('Clear'),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (_busy) const LinearProgressIndicator(),
            if (_error != null)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    children: [
                      Text(_error!, key: const ValueKey('cart-error')),
                      TextButton(
                        onPressed: _busy
                            ? null
                            : () => _run(widget.repository.load),
                        child: const Text('Retry'),
                      ),
                    ],
                  ),
                ),
              ),
            if (cart == null && _error == null)
              const Center(child: CircularProgressIndicator()),
            if (cart != null && cart.items.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 72),
                child: Column(
                  children: [
                    Icon(Icons.shopping_cart_outlined, size: 56),
                    SizedBox(height: 12),
                    Text('Your cart is empty'),
                  ],
                ),
              ),
            if (cart != null && cart.items.isNotEmpty) ...[
              Text(
                'Course items',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 12),
              for (final item in cart.items) _itemCard(item),
              const SizedBox(height: 12),
              _summary(cart),
            ],
          ],
        ),
      ),
    );
  }

  Widget _itemCard(CartItem item) {
    final supported = item.isCourseCheckoutItem;
    final isCombo = item.type == CartItemType.combo;
    final available =
        supported && item.availability == CartItemAvailability.available;
    return Card(
      key: ValueKey('cart-item-${item.id}'),
      child: ListTile(
        leading: Icon(
          isCombo ? Icons.collections_bookmark_outlined : Icons.school_outlined,
        ),
        title: Text(item.name),
        subtitle: Text(
          available
              ? '${isCombo ? 'Combo' : 'Course'} · quantity 1 · ${formatVnd(item.unitPriceVnd!)} VND'
              : item.availabilityMessage ??
                    (supported
                        ? (isCombo
                              ? 'This Combo is unavailable.'
                              : 'This Course is unavailable.')
                        : 'Product purchases are not supported in Course-only checkout.'),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (available) Text(formatVnd(item.subtotalVnd)),
            IconButton(
              tooltip: 'Remove ${item.name}',
              onPressed: _busy
                  ? null
                  : () => _run(() => widget.repository.remove(item.id)),
              icon: const Icon(Icons.close),
            ),
          ],
        ),
      ),
    );
  }

  Widget _summary(CartSnapshot cart) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Order estimate', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 12),
          Text(
            'Current Course subtotal: ${formatVnd(cart.subtotalVnd)} VND',
            key: const ValueKey('cart-total'),
          ),
          const SizedBox(height: 8),
          const Text(
            'This is the current catalog total, not a price guarantee. Any voucher is validated by the server and its final discount is shown in the saved Order.',
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            key: const ValueKey('start-course-checkout'),
            onPressed:
                _busy ||
                    !cart.canCheckout ||
                    cart.items.any(
                      (item) =>
                          !item.isCourseCheckoutItem ||
                          item.availability != CartItemAvailability.available,
                    )
                ? null
                : () => context.push('/checkout', extra: cart),
            icon: const Icon(Icons.lock_outline),
            label: const Text('Continue to checkout'),
          ),
          if (!cart.canCheckout || cart.hasUnavailableItems)
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text('Remove unavailable items before checkout.'),
            ),
        ],
      ),
    ),
  );
}
