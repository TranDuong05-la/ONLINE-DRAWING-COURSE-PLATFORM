import 'package:flutter/material.dart';

import '../../core/network/api_exceptions.dart';
import 'data/order_history_repository.dart';
import 'models/order_history_models.dart';
import 'state/order_history_controller.dart';
import 'utils/order_history_formatters.dart';

class OrderDetailScreen extends StatefulWidget {
  const OrderDetailScreen({
    super.key,
    required this.orderId,
    required this.repository,
    this.onSessionExpired,
  });
  final int? orderId;
  final OrderHistoryRepository repository;
  final SessionExpiryHandler? onSessionExpired;

  @override
  State<OrderDetailScreen> createState() => _OrderDetailScreenState();
}

class _OrderDetailScreenState extends State<OrderDetailScreen> {
  late Future<OrderDetail> _detail;

  @override
  void initState() {
    super.initState();
    _detail = _load();
  }

  Future<OrderDetail> _load() async {
    final id = widget.orderId;
    if (id == null || id <= 0) {
      throw const OrderHistoryFormatException('Order ID is invalid.');
    }
    try {
      return await widget.repository.getOrderDetail(id);
    } on UnauthorizedException {
      await widget.onSessionExpired?.call();
      rethrow;
    }
  }

  void _retry() => setState(() => _detail = _load());

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Order Detail')),
    body: FutureBuilder<OrderDetail>(
      future: _detail,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) return _errorState(context, snapshot.error!);
        final order = snapshot.data!;
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(
              'Order #${order.orderId}',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                Chip(label: Text(orderStatusLabel(order.status.wireName))),
                Chip(
                  label: Text(
                    'Payment: ${paymentStatusLabel(order.paymentStatus.wireName)}',
                  ),
                ),
              ],
            ),
            _info('Created', formatOrderDate(order.createdAt)),
            _info(
              'Payment deadline',
              order.paymentExpiresAt == null
                  ? 'Not set'
                  : formatOrderDate(order.paymentExpiresAt!),
            ),
            const SizedBox(height: 16),
            Text('Items', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            for (final item in order.items) _itemCard(context, item),
            const SizedBox(height: 16),
            Text('Order totals', style: Theme.of(context).textTheme.titleLarge),
            _moneyRow('Merchandise subtotal', order.merchandiseSubtotalVnd),
            _moneyRow('Eligible subtotal', order.eligibleSubtotalVnd),
            _moneyRow('Discount', -order.discountVnd),
            _moneyRow('Shipping fee', order.shippingFeeVnd),
            const Divider(height: 24),
            _moneyRow('Total', order.totalVnd, emphasis: true),
            if (order.voucher != null) ...[
              const SizedBox(height: 20),
              Text(
                'Voucher snapshot',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              _info('Code', order.voucher!.code),
              _info(
                'Rule',
                '${order.voucher!.kind.wireName} · ${order.voucher!.value}',
              ),
              _info(
                'Maximum discount',
                formatOrderVnd(order.voucher!.maximumDiscountVnd),
              ),
            ],
            if (order.shippingAddress != null) ...[
              const SizedBox(height: 20),
              Text(
                'Shipping address snapshot',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              _info('Recipient', order.shippingAddress!.recipient),
              _info('Phone', order.shippingAddress!.phone),
              _info(
                'Address',
                '${order.shippingAddress!.addressLine}, ${order.shippingAddress!.locality}',
              ),
              _info('Postal code', order.shippingAddress!.postalCode),
              _info('Country', order.shippingAddress!.countryCode),
            ],
          ],
        );
      },
    ),
  );

  Widget _itemCard(BuildContext context, OrderItem item) => Card(
    key: ValueKey('order-line-${item.orderItemId}'),
    margin: const EdgeInsets.only(bottom: 10),
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            item.nameSnapshot,
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 4),
          Text(
            '${item.itemType.wireName} · ${item.quantity} × ${formatOrderVnd(item.unitPriceVnd)}',
          ),
          const SizedBox(height: 8),
          _moneyRow('Gross', item.grossAmountVnd),
          _moneyRow('Allocated discount', -item.allocatedDiscountVnd),
          _moneyRow('Net paid', item.netAmountVnd, emphasis: true),
        ],
      ),
    ),
  );

  Widget _moneyRow(String label, int amount, {bool emphasis = false}) =>
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Expanded(
              child: Text(
                label,
                style: emphasis
                    ? const TextStyle(fontWeight: FontWeight.bold)
                    : null,
              ),
            ),
            Text(
              '${amount < 0 ? '−' : ''}${formatOrderVnd(amount.abs())}',
              style: emphasis
                  ? const TextStyle(fontWeight: FontWeight.bold)
                  : null,
            ),
          ],
        ),
      );

  Widget _info(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 4),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(width: 150, child: Text(label)),
        Expanded(child: Text(value)),
      ],
    ),
  );

  Widget _errorState(BuildContext context, Object error) {
    final notFound = error is NotFoundException;
    final message = notFound
        ? 'This Order is not available.'
        : error is NetworkException
        ? 'Could not load this Order. Check your connection and retry.'
        : error is ApiException && error.message.isNotEmpty
        ? error.message
        : error is OrderHistoryFormatException
        ? 'The Order response could not be read.'
        : 'Could not load this Order. Check your connection and retry.';
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              notFound ? Icons.receipt_long_outlined : Icons.cloud_off_outlined,
              size: 48,
            ),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            OutlinedButton(onPressed: _retry, child: const Text('Retry')),
          ],
        ),
      ),
    );
  }
}
