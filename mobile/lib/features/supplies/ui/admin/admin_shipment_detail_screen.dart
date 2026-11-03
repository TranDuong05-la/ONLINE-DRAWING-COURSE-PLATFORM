import 'package:flutter/material.dart';

import '../widgets/supply_states.dart';
import '../../models/supply_models.dart';
import '../../data/supply_repository.dart';
import '../widgets/supply_widgets.dart';

/// Admin: danh sách cần đóng gói + cập nhật trạng thái giao.
class AdminShipmentDetailScreen extends StatefulWidget {
  final int shipmentId;
  const AdminShipmentDetailScreen({super.key, required this.shipmentId});

  @override
  State<AdminShipmentDetailScreen> createState() => _AdminShipmentDetailScreenState();
}

class _AdminShipmentDetailScreenState extends State<AdminShipmentDetailScreen> {
  Shipment? _shipment;
  Object? _error;
  bool _updating = false;
  final Set<int> _packed = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final s = await SupplyRepository.instance.managerShipment(widget.shipmentId);
      if (mounted) setState(() => _shipment = s);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  bool get _allPacked => _shipment != null && _packed.length == _shipment!.items.length;

  Future<void> _changeStatus(ShipmentStatus to) async {
    final s = _shipment!;
    final input = await showDialog<_StatusInput>(
      context: context,
      builder: (_) => _StatusDialog(shipment: s, to: to),
    );
    if (input == null) return;
    setState(() => _updating = true);
    try {
      final updated = await SupplyRepository.instance.updateShipmentStatus(
        s.id,
        to,
        carrier: input.carrier,
        trackingCode: input.trackingCode,
        note: input.note,
      );
      if (!mounted) return;
      setState(() => _shipment = updated);
      showMessage(context, 'Đã chuyển sang "${to.label}"');
    } catch (e) {
      if (!mounted) return;
      showMessage(context, e.toString());
      _load(); // trạng thái có thể đã đổi ở nơi khác
    } finally {
      if (mounted) setState(() => _updating = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = _shipment;
    return Scaffold(
      appBar: AppBar(title: Text(s == null ? 'Giao hàng' : 'Giao đơn #${s.orderId}')),
      body: s == null
          ? (_error != null ? ErrorView(error: _error!, onRetry: _load) : const LoadingView())
          : RefreshIndicator(onRefresh: _load, child: _buildContent(s)),
      bottomNavigationBar: s == null || s.nextStatuses.isEmpty ? null : _buildActions(s),
    );
  }

  Widget _buildContent(Shipment s) {
    final text = Theme.of(context).textTheme;
    final preparing = s.status == ShipmentStatus.preparing;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Text('Mã giao #${s.id}', style: text.titleMedium),
            const Spacer(),
            ShipmentStatusChip(status: s.status),
          ],
        ),
        const SizedBox(height: 12),
        Card(
          margin: EdgeInsets.zero,
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.person_outline),
                title: Text(s.recipientName),
                subtitle: Text('${s.recipientPhone}\n${s.shippingAddress}'),
                isThreeLine: true,
              ),
              if (s.carrier != null || s.trackingCode != null)
                ListTile(
                  leading: const Icon(Icons.qr_code_2),
                  title: Text(s.carrier ?? 'Vận chuyển'),
                  subtitle: Text(s.trackingCode ?? ''),
                ),
              if (s.note != null)
                ListTile(leading: const Icon(Icons.sticky_note_2_outlined), title: Text(s.note!)),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Text(
              preparing ? 'Cần đóng gói (${s.totalQuantity})' : 'Sản phẩm (${s.totalQuantity})',
              style: text.titleMedium,
            ),
            const Spacer(),
            if (preparing) Text('${_packed.length}/${s.items.length} đã soạn', style: text.bodySmall),
          ],
        ),
        const SizedBox(height: 8),
        for (final item in s.items)
          Card(
            margin: const EdgeInsets.only(bottom: 8),
            child: preparing
                ? CheckboxListTile(
                    value: _packed.contains(item.productId * 2 + (item.gift ? 1 : 0)),
                    onChanged: (v) => setState(() {
                      final key = item.productId * 2 + (item.gift ? 1 : 0);
                      v == true ? _packed.add(key) : _packed.remove(key);
                    }),
                    secondary: SupplyImage(url: item.thumbnailUrl, size: 48),
                    title: Text(item.productName),
                    subtitle: _itemSubtitle(item),
                  )
                : ListTile(
                    leading: SupplyImage(url: item.thumbnailUrl, size: 48),
                    title: Text(item.productName),
                    subtitle: _itemSubtitle(item),
                  ),
          ),
        const SizedBox(height: 8),
        Text('Lịch trình', style: text.titleMedium),
        const SizedBox(height: 8),
        ShipmentTimeline(shipment: s),
        if (s.status == ShipmentStatus.failed)
          Card(
            color: Colors.orange.withValues(alpha: 0.1),
            child: const Padding(
              padding: EdgeInsets.all(12),
              child: Text(
                'Giao thất bại không tự nhập lại kho. Chọn "Giao lại" nếu hàng còn ở '
                'đơn vị vận chuyển, hoặc "Hàng đã về kho" khi đã nhận lại hàng để cộng tồn.',
              ),
            ),
          ),
      ],
    );
  }

  Widget _itemSubtitle(ShipmentItem item) {
    return Row(
      children: [
        Text('SL: ${item.quantity} · ${item.sku}'),
        if (item.gift) ...[const SizedBox(width: 8), const GiftTag()],
      ],
    );
  }

  Widget _buildActions(Shipment s) {
    final needPack = s.status == ShipmentStatus.preparing && !_allPacked;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (needPack)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Text(
                  'Đánh dấu đã soạn đủ hàng trước khi giao',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
            Row(
              children: [
                for (final (i, to) in s.nextStatuses.indexed) ...[
                  if (i > 0) const SizedBox(width: 8),
                  Expanded(
                    child: to == ShipmentStatus.failed || to == ShipmentStatus.returned
                        ? OutlinedButton(
                            onPressed: _updating ? null : () => _changeStatus(to),
                            child: Text(_label(s, to), textAlign: TextAlign.center),
                          )
                        : FilledButton(
                            onPressed: _updating || needPack ? null : () => _changeStatus(to),
                            child: Text(_label(s, to), textAlign: TextAlign.center),
                          ),
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }

  String _label(Shipment s, ShipmentStatus to) =>
      s.status == ShipmentStatus.failed && to == ShipmentStatus.shipping ? 'Giao lại' : to.actionLabel;
}

class _StatusInput {
  final String? carrier;
  final String? trackingCode;
  final String? note;
  _StatusInput(this.carrier, this.trackingCode, this.note);
}

class _StatusDialog extends StatefulWidget {
  final Shipment shipment;
  final ShipmentStatus to;
  const _StatusDialog({required this.shipment, required this.to});

  @override
  State<_StatusDialog> createState() => _StatusDialogState();
}

class _StatusDialogState extends State<_StatusDialog> {
  late final _carrier = TextEditingController(text: widget.shipment.carrier);
  late final _tracking = TextEditingController(text: widget.shipment.trackingCode);
  final _note = TextEditingController();

  @override
  void dispose() {
    _carrier.dispose();
    _tracking.dispose();
    _note.dispose();
    super.dispose();
  }

  String get _explain => switch (widget.to) {
    ShipmentStatus.shipping when widget.shipment.status == ShipmentStatus.preparing =>
      'Hàng sẽ được xuất kho (trừ tồn thực tế). Không thể hủy phần học cụ sau bước này.',
    ShipmentStatus.shipping => 'Giao lại đơn. Không trừ kho thêm lần nữa.',
    ShipmentStatus.delivered => 'Xác nhận học viên đã nhận hàng. Học viên có thể đánh giá học cụ.',
    ShipmentStatus.failed => 'Đánh dấu giao thất bại. Kho chưa được cộng lại.',
    ShipmentStatus.returned => 'Xác nhận hàng đã về kho: số lượng sẽ được cộng lại vào tồn.',
    _ => '',
  };

  @override
  Widget build(BuildContext context) {
    final showTracking = widget.to == ShipmentStatus.shipping;
    return AlertDialog(
      title: Text(widget.to.actionLabel),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_explain),
            if (showTracking) ...[
              TextField(
                controller: _carrier,
                decoration: const InputDecoration(labelText: 'Đơn vị vận chuyển (vd. GHN)'),
              ),
              TextField(
                controller: _tracking,
                decoration: const InputDecoration(labelText: 'Mã vận đơn'),
              ),
            ],
            TextField(
              controller: _note,
              decoration: InputDecoration(
                labelText: widget.to == ShipmentStatus.failed ? 'Lý do thất bại' : 'Ghi chú',
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Hủy')),
        FilledButton(
          onPressed: () => Navigator.pop(
            context,
            _StatusInput(
              showTracking ? _carrier.text.trim() : null,
              showTracking ? _tracking.text.trim() : null,
              _note.text.trim().isEmpty ? null : _note.text.trim(),
            ),
          ),
          child: const Text('Xác nhận'),
        ),
      ],
    );
  }
}
