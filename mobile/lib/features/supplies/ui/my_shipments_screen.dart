import 'package:flutter/material.dart';

import 'widgets/supply_format.dart';
import 'widgets/supply_states.dart';
import '../models/supply_models.dart';
import '../data/supply_repository.dart';
import 'shipment_detail_screen.dart';
import 'widgets/supply_widgets.dart';

/// Học viên xem các đơn giao học cụ/quà của mình.
class MyShipmentsScreen extends StatefulWidget {
  const MyShipmentsScreen({super.key});

  @override
  State<MyShipmentsScreen> createState() => _MyShipmentsScreenState();
}

class _MyShipmentsScreenState extends State<MyShipmentsScreen> {
  late Future<List<Shipment>> _future = SupplyRepository.instance.myShipments();

  Future<void> _refresh() async {
    final f = SupplyRepository.instance.myShipments();
    setState(() => _future = f);
    await f.catchError((_) => <Shipment>[]);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Đơn giao hàng của tôi')),
      body: FutureBuilder<List<Shipment>>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) return const LoadingView();
          if (snap.hasError) return ErrorView(error: snap.error!, onRetry: _refresh);
          final list = snap.data!;
          if (list.isEmpty) {
            return RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                children: const [
                  SizedBox(height: 120),
                  EmptyView(
                    message: 'Chưa có đơn giao hàng.\nĐơn có học cụ hoặc quà kèm khóa học sẽ hiện ở đây.',
                    icon: Icons.local_shipping_outlined,
                  ),
                ],
              ),
            );
          }
          return RefreshIndicator(
            onRefresh: _refresh,
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: list.length,
              separatorBuilder: (_, _) => const SizedBox(height: 12),
              itemBuilder: (context, i) => _ShipmentCard(
                shipment: list[i],
                onTap: () async {
                  await Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => ShipmentDetailScreen(shipmentId: list[i].id)),
                  );
                  _refresh();
                },
              ),
            ),
          );
        },
      ),
    );
  }
}

class _ShipmentCard extends StatelessWidget {
  final Shipment shipment;
  final VoidCallback onTap;
  const _ShipmentCard({required this.shipment, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final s = shipment;
    final text = Theme.of(context).textTheme;
    final first = s.items.isEmpty ? null : s.items.first;
    return Card(
      margin: EdgeInsets.zero,
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text('Đơn #${s.orderId}', style: text.titleSmall),
                  const Spacer(),
                  ShipmentStatusChip(status: s.status),
                ],
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  SupplyImage(url: first?.thumbnailUrl, size: 56),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(first?.productName ?? '', maxLines: 1, overflow: TextOverflow.ellipsis),
                        if (s.items.length > 1)
                          Text('và ${s.items.length - 1} món khác', style: text.bodySmall),
                        Text(
                          '${s.totalQuantity} sản phẩm · ${formatDateTime(s.createdAt)}',
                          style: text.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  const Icon(Icons.chevron_right),
                ],
              ),
              if (s.status == ShipmentStatus.delivered) ...[
                const SizedBox(height: 8),
                Text(
                  'Đã nhận hàng, bạn có thể đánh giá học cụ.',
                  style: text.bodySmall?.copyWith(color: Colors.green.shade700),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
