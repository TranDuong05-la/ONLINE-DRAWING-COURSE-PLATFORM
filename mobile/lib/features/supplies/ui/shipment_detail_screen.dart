import 'package:flutter/material.dart';

import 'widgets/supply_states.dart';
import '../models/supply_models.dart';
import '../data/supply_repository.dart';
import 'review_form_sheet.dart';
import 'widgets/supply_widgets.dart';

/// Học viên xem chi tiết giao hàng và đánh giá sau khi nhận.
class ShipmentDetailScreen extends StatefulWidget {
  final int shipmentId;
  const ShipmentDetailScreen({super.key, required this.shipmentId});

  @override
  State<ShipmentDetailScreen> createState() => _ShipmentDetailScreenState();
}

class _ShipmentDetailScreenState extends State<ShipmentDetailScreen> {
  Shipment? _shipment;
  Object? _error;
  final Map<int, ReviewEligibility> _eligibility = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final s = await SupplyRepository.instance.shipment(widget.shipmentId);
      if (!mounted) return;
      setState(() => _shipment = s);
      if (s.status == ShipmentStatus.delivered) _loadEligibility(s);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _loadEligibility(Shipment s) async {
    for (final item in s.items) {
      try {
        final e = await SupplyRepository.instance.reviewEligibility(item.productId);
        if (mounted) setState(() => _eligibility[item.productId] = e);
      } catch (_) {}
    }
  }

  Future<void> _review(ShipmentItem item) async {
    final saved = await showReviewForm(
      context,
      productId: item.productId,
      productName: item.productName,
      existing: _eligibility[item.productId]?.myReview,
    );
    if (saved && mounted) {
      showMessage(context, 'Cảm ơn bạn đã đánh giá!');
      _loadEligibility(_shipment!);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = _shipment;
    return Scaffold(
      appBar: AppBar(title: Text(s == null ? 'Giao hàng' : 'Đơn #${s.orderId}')),
      body: s == null
          ? (_error != null ? ErrorView(error: _error!, onRetry: _load) : const LoadingView())
          : RefreshIndicator(onRefresh: _load, child: _buildContent(s)),
    );
  }

  Widget _buildContent(Shipment s) {
    final text = Theme.of(context).textTheme;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Card(
          margin: EdgeInsets.zero,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text('Trạng thái', style: text.titleMedium),
                    const Spacer(),
                    ShipmentStatusChip(status: s.status),
                  ],
                ),
                const SizedBox(height: 16),
                ShipmentTimeline(shipment: s),
                if (s.trackingCode != null || s.carrier != null)
                  Text('Vận chuyển: ${s.carrier ?? ''} ${s.trackingCode ?? ''}'.trim()),
                if (s.note != null) ...[
                  const SizedBox(height: 4),
                  Text('Ghi chú: ${s.note}', style: text.bodySmall),
                ],
                if (s.status == ShipmentStatus.failed) ...[
                  const SizedBox(height: 8),
                  Text(
                    'Giao chưa thành công. Cửa hàng sẽ liên hệ để giao lại.',
                    style: text.bodySmall?.copyWith(color: Colors.red.shade700),
                  ),
                ],
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          margin: EdgeInsets.zero,
          child: ListTile(
            leading: const Icon(Icons.place_outlined),
            title: Text('${s.recipientName} · ${s.recipientPhone}'),
            subtitle: Text(s.shippingAddress),
          ),
        ),
        const SizedBox(height: 16),
        Text('Sản phẩm (${s.totalQuantity})', style: text.titleMedium),
        const SizedBox(height: 8),
        for (final item in s.items) _itemTile(s, item),
      ],
    );
  }

  Widget _itemTile(Shipment s, ShipmentItem item) {
    final elig = _eligibility[item.productId];
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        children: [
          SupplyImage(url: item.thumbnailUrl, size: 60),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.productName, maxLines: 2, overflow: TextOverflow.ellipsis),
                const SizedBox(height: 2),
                Row(
                  children: [
                    Text('x${item.quantity}', style: Theme.of(context).textTheme.bodySmall),
                    if (item.gift) ...[const SizedBox(width: 8), const GiftTag()],
                  ],
                ),
              ],
            ),
          ),
          if (s.status == ShipmentStatus.delivered && elig != null && elig.canReview)
            TextButton(
              onPressed: () => _review(item),
              child: Text(elig.myReview == null ? 'Đánh giá' : 'Sửa đánh giá'),
            ),
        ],
      ),
    );
  }
}
