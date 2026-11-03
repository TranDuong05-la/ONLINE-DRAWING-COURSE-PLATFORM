import 'package:flutter/material.dart';

import 'widgets/supply_format.dart';
import 'widgets/supply_states.dart';
import '../models/supply_models.dart';
import '../data/supply_repository.dart';

/// CHỈ HIỆN Ở BẢN DEBUG khi giỏ hàng của D chưa ghép.
/// Gọi /api/dev/supply/orders: giữ hàng + giả lập thanh toán + tạo chuyến giao,
/// để test luồng kho -> giao -> đánh giá của E.
Future<bool> showDevOrderSheet(
  BuildContext context, {
  required SupplyProduct product,
  required int quantity,
}) async {
  final ok = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => _DevOrderSheet(product: product, quantity: quantity),
  );
  return ok ?? false;
}

class _DevOrderSheet extends StatefulWidget {
  final SupplyProduct product;
  final int quantity;
  const _DevOrderSheet({required this.product, required this.quantity});

  @override
  State<_DevOrderSheet> createState() => _DevOrderSheetState();
}

class _DevOrderSheetState extends State<_DevOrderSheet> {
  final _name = TextEditingController(text: 'Nguyễn Văn A');
  final _phone = TextEditingController(text: '0901234567');
  final _address = TextEditingController(text: '7 Đ. D1, Long Thạnh Mỹ, Thủ Đức, TP.HCM');
  bool _saving = false;

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    _address.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() => _saving = true);
    try {
      await SupplyRepository.instance.devOrder(
        lines: [
          {'productId': widget.product.id, 'quantity': widget.quantity, 'gift': false},
        ],
        recipientName: _name.text,
        phone: _phone.text,
        address: _address.text,
      );
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) {
        setState(() => _saving = false);
        showMessage(context, e.toString());
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = widget.product;
    return Padding(
      padding: EdgeInsets.fromLTRB(20, 0, 20, MediaQuery.of(context).viewInsets.bottom + 20),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Đặt thử (dev)', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 4),
          const Text('Giỏ hàng của D chưa ghép. Đặt thử sẽ giữ hàng, giả lập đã thanh toán và tạo đơn giao.'),
          const SizedBox(height: 12),
          Text(
            '${widget.quantity} × ${p.name} = ${formatVnd(p.price * widget.quantity)}',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _name,
            decoration: const InputDecoration(labelText: 'Người nhận'),
          ),
          TextField(
            controller: _phone,
            keyboardType: TextInputType.phone,
            decoration: const InputDecoration(labelText: 'Số điện thoại'),
          ),
          TextField(
            controller: _address,
            decoration: const InputDecoration(labelText: 'Địa chỉ'),
          ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _saving ? null : _submit,
            child: _saving
                ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Đặt và thanh toán thử'),
          ),
        ],
      ),
    );
  }
}
