import 'package:flutter/material.dart';

import 'widgets/supply_states.dart';
import '../models/supply_models.dart';
import '../data/supply_repository.dart';
import 'widgets/supply_widgets.dart';

/// Mở form viết/sửa đánh giá. Trả về true nếu đã lưu.
Future<bool> showReviewForm(
  BuildContext context, {
  required int productId,
  required String productName,
  Review? existing,
}) async {
  final saved = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => _ReviewForm(productId: productId, productName: productName, existing: existing),
  );
  return saved ?? false;
}

class _ReviewForm extends StatefulWidget {
  final int productId;
  final String productName;
  final Review? existing;
  const _ReviewForm({required this.productId, required this.productName, this.existing});

  @override
  State<_ReviewForm> createState() => _ReviewFormState();
}

class _ReviewFormState extends State<_ReviewForm> {
  late int _rating = widget.existing?.rating ?? 0;
  late final _comment = TextEditingController(text: widget.existing?.comment ?? '');
  bool _saving = false;

  @override
  void dispose() {
    _comment.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_rating == 0) {
      showMessage(context, 'Chọn số sao trước khi gửi');
      return;
    }
    setState(() => _saving = true);
    try {
      final repo = SupplyRepository.instance;
      final text = _comment.text.trim();
      if (widget.existing == null) {
        await repo.createReview(widget.productId, _rating, text.isEmpty ? null : text);
      } else {
        await repo.updateReview(widget.existing!.id, _rating, text.isEmpty ? null : text);
      }
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
    return Padding(
      padding: EdgeInsets.fromLTRB(20, 0, 20, MediaQuery.of(context).viewInsets.bottom + 20),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            widget.existing == null ? 'Đánh giá học cụ' : 'Sửa đánh giá',
            style: Theme.of(context).textTheme.titleLarge,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 4),
          Text(widget.productName, textAlign: TextAlign.center, maxLines: 2),
          const SizedBox(height: 8),
          RatingInput(value: _rating, onChanged: (v) => setState(() => _rating = v)),
          const SizedBox(height: 8),
          TextField(
            controller: _comment,
            maxLines: 4,
            maxLength: 1000,
            decoration: const InputDecoration(
              hintText: 'Chia sẻ cảm nhận về chất lượng học cụ (không bắt buộc)',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 8),
          FilledButton(
            onPressed: _saving ? null : _submit,
            child: _saving
                ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : Text(widget.existing == null ? 'Gửi đánh giá' : 'Lưu thay đổi'),
          ),
        ],
      ),
    );
  }
}
