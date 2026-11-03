import 'package:flutter/material.dart';

import '../widgets/supply_format.dart';
import '../widgets/supply_states.dart';
import '../../models/supply_models.dart';
import '../../data/supply_repository.dart';
import '../widgets/supply_widgets.dart';
import 'admin_shipment_detail_screen.dart';

/// Admin: danh sách giao hàng theo trạng thái. Mặc định "Chuẩn bị" = hàng/quà cần đóng gói.
class AdminShipmentsTab extends StatefulWidget {
  const AdminShipmentsTab({super.key});

  @override
  State<AdminShipmentsTab> createState() => _AdminShipmentsTabState();
}

class _AdminShipmentsTabState extends State<AdminShipmentsTab> with AutomaticKeepAliveClientMixin {
  final _repo = SupplyRepository.instance;
  ShipmentStatus _status = ShipmentStatus.preparing;
  ShipmentSummary? _summary;
  final List<Shipment> _items = [];
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

  Future<void> _reload() async {
    _requestId++;
    setState(() {
      _items.clear();
      _page = 0;
      _hasMore = true;
      _error = null;
      _loading = false;
    });
    _repo
        .shipmentSummary()
        .then((s) {
          if (mounted) setState(() => _summary = s);
        })
        .catchError((_) {});
    await _loadMore();
  }

  Future<void> _loadMore() async {
    if (_loading || !_hasMore) return;
    final id = _requestId;
    setState(() => _loading = true);
    try {
      final r = await _repo.managerShipments(_status, page: _page);
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

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Column(
      children: [
        SizedBox(
          height: 52,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            children: [
              for (final s in ShipmentStatus.values)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(
                    avatar: Icon(s.icon, size: 16),
                    label: Text(_summary == null ? s.label : '${s.label} (${_summary!.counts[s] ?? 0})'),
                    selected: _status == s,
                    onSelected: (_) {
                      setState(() => _status = s);
                      _reload();
                    },
                  ),
                ),
            ],
          ),
        ),
        Expanded(child: _buildList()),
      ],
    );
  }

  Widget _buildList() {
    if (_items.isEmpty) {
      if (_loading) return const LoadingView();
      if (_error != null) return ErrorView(error: _error!, onRetry: _reload);
      return RefreshIndicator(
        onRefresh: _reload,
        child: ListView(
          children: [
            const SizedBox(height: 100),
            EmptyView(
              message: _status == ShipmentStatus.preparing
                  ? 'Không có đơn nào cần đóng gói'
                  : 'Không có đơn "${_status.label}"',
              icon: _status.icon,
            ),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _reload,
      child: NotificationListener<ScrollNotification>(
        onNotification: (n) {
          if (n.metrics.pixels > n.metrics.maxScrollExtent - 200) _loadMore();
          return false;
        },
        child: ListView.separated(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          itemCount: _items.length + (_hasMore ? 1 : 0),
          separatorBuilder: (_, _) => const SizedBox(height: 10),
          itemBuilder: (context, i) {
            if (i >= _items.length) return const Padding(padding: EdgeInsets.all(16), child: LoadingView());
            final s = _items[i];
            return _AdminShipmentCard(
              shipment: s,
              onTap: () async {
                await Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => AdminShipmentDetailScreen(shipmentId: s.id)),
                );
                _reload();
              },
            );
          },
        ),
      ),
    );
  }
}

class _AdminShipmentCard extends StatelessWidget {
  final Shipment shipment;
  final VoidCallback onTap;
  const _AdminShipmentCard({required this.shipment, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final s = shipment;
    final text = Theme.of(context).textTheme;
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
                  const SizedBox(width: 8),
                  if (s.hasGift) const GiftTag(),
                  const Spacer(),
                  ShipmentStatusChip(status: s.status),
                ],
              ),
              const SizedBox(height: 6),
              Text('${s.recipientName} · ${s.recipientPhone}', style: text.bodyMedium),
              Text(s.shippingAddress, style: text.bodySmall, maxLines: 1, overflow: TextOverflow.ellipsis),
              const Divider(height: 16),
              for (final item in s.items.take(3))
                Padding(
                  padding: const EdgeInsets.only(bottom: 2),
                  child: Row(
                    children: [
                      Text('${item.quantity} ×  ', style: const TextStyle(fontWeight: FontWeight.w600)),
                      Expanded(child: Text(item.productName, maxLines: 1, overflow: TextOverflow.ellipsis)),
                      if (item.gift) Icon(Icons.card_giftcard, size: 14, color: Colors.pink.shade400),
                    ],
                  ),
                ),
              if (s.items.length > 3) Text('+ ${s.items.length - 3} món khác', style: text.bodySmall),
              const SizedBox(height: 4),
              Text(
                'Tạo lúc ${formatDateTime(s.createdAt)}${s.attempts > 1 ? ' · Giao lần ${s.attempts}' : ''}',
                style: text.bodySmall,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
