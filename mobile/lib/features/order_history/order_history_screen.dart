import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/network/api_exceptions.dart';
import '../../widgets/empty_state_view.dart';
import 'data/order_history_repository.dart';
import 'models/order_history_models.dart';
import 'state/order_history_controller.dart';
import 'utils/order_history_formatters.dart';

class OrderHistoryScreen extends StatefulWidget {
  const OrderHistoryScreen({
    super.key,
    required this.repository,
    this.onSessionExpired,
  });
  final OrderHistoryRepository repository;
  final SessionExpiryHandler? onSessionExpired;

  @override
  State<OrderHistoryScreen> createState() => _OrderHistoryScreenState();
}

class _OrderHistoryScreenState extends State<OrderHistoryScreen> {
  late final OrderHistoryController _controller;

  @override
  void initState() {
    super.initState();
    _controller = OrderHistoryController(
      repository: widget.repository,
      onSessionExpired: widget.onSessionExpired,
    )..loadFirstPage();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Order History')),
    body: AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        if (_controller.isLoading && _controller.orders.isEmpty) {
          return const Center(child: CircularProgressIndicator());
        }
        if (_controller.error != null && _controller.orders.isEmpty) {
          return _failure(
            context,
            _controller.error!,
            () => _controller.retry(),
          );
        }
        if (_controller.isEmpty) {
          return const EmptyStateView(
            icon: Icons.receipt_long_outlined,
            title: 'No orders yet',
            message: 'Orders will appear here after a purchase. No sample orders are shown.',
          );
        }
        return RefreshIndicator(
          onRefresh: _controller.loadFirstPage,
          child: ListView(
            key: const ValueKey('order-history-list'),
            padding: const EdgeInsets.all(16),
            children: [
              for (final order in _controller.orders)
                _orderCard(context, order),
              if (_controller.error != null)
                _failure(
                  context,
                  _controller.error!,
                  () => _controller.retry(),
                  compact: true,
                ),
              if (_controller.hasMore && _controller.error == null)
                Center(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: _controller.isLoadingMore
                        ? const CircularProgressIndicator()
                        : TextButton.icon(
                            key: const ValueKey('load-more-orders'),
                            onPressed: _controller.loadMore,
                            icon: const Icon(Icons.expand_more),
                            label: const Text('Load more'),
                          ),
                  ),
                ),
            ],
          ),
        );
      },
    ),
  );

  Widget _orderCard(BuildContext context, OrderSummary order) => Card(
    key: ValueKey('order-${order.orderId}'),
    margin: const EdgeInsets.only(bottom: 12),
    child: ListTile(
      contentPadding: const EdgeInsets.all(16),
      onTap: () => context.push('/orders/${order.orderId}'),
      title: Row(
        children: [
          Expanded(
            child: Text(
              'Order #${order.orderId}',
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          _StatusChip(label: orderStatusLabel(order.status.wireName)),
        ],
      ),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Payment: ${paymentStatusLabel(order.paymentStatus.wireName)}',
            ),
            const SizedBox(height: 4),
            Text('Placed ${formatOrderDate(order.createdAt)}'),
            const SizedBox(height: 4),
            Text('${order.itemCount} item${order.itemCount == 1 ? '' : 's'}'),
            const SizedBox(height: 8),
            Text(
              formatOrderVnd(order.totalVnd),
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ],
        ),
      ),
      trailing: const Icon(Icons.chevron_right),
    ),
  );

  Widget _failure(
    BuildContext context,
    Object error,
    VoidCallback retry, {
    bool compact = false,
  }) {
    final message = error is NotFoundException
        ? 'This Order is not available.'
        : error is NetworkException
        ? 'Could not load Order History. Check your connection and retry.'
        : error is ApiException && error.message.isNotEmpty
        ? error.message
        : error is OrderHistoryFormatException
        ? 'The Order History response could not be read.'
        : 'Could not load Order History. Check your connection and retry.';
    if (compact) {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Text(message),
            TextButton(onPressed: retry, child: const Text('Retry')),
          ],
        ),
      );
    }
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.cloud_off_outlined, size: 48),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton(onPressed: retry, child: const Text('Retry')),
          ],
        ),
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.label});
  final String label;
  @override
  Widget build(BuildContext context) => Chip(
    visualDensity: VisualDensity.compact,
    label: Text(label, style: Theme.of(context).textTheme.labelSmall),
  );
}
