import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';

import '../../core/constants/app_colors.dart';
import '../learning/learning_widgets.dart';
import '../order_history/utils/order_history_formatters.dart';
import 'refund_repository.dart';

Color refundStatusColor(String status) => switch (status) {
      'REFUNDED' => AppColors.success,
      'REJECTED' => AppColors.textSecondary,
      'FAILED' => AppColors.error,
      'VERIFYING' => AppColors.warning,
      'PENDING' => AppColors.accentPeach,
      _ => AppColors.info,
    };

String _date(DateTime? d) => d == null ? '' : DateFormat('dd/MM/yyyy HH:mm').format(d);

// ======================================================================= trong chi tiết đơn hàng

/// Khối "Hoàn tiền" dưới một dòng khóa học trong chi tiết đơn hàng.
class RefundItemSection extends StatefulWidget {
  const RefundItemSection({super.key, required this.orderId, required this.orderItemId});

  final int orderId;
  final int orderItemId;

  @override
  State<RefundItemSection> createState() => _RefundItemSectionState();
}

class _RefundItemSectionState extends State<RefundItemSection> {
  final _repo = RefundRepository();
  int _reload = 0;

  Future<void> _ask(RefundEligibility e) async {
    final sent = await showDialog<bool>(
      context: context,
      builder: (_) => _RefundRequestDialog(
          repo: _repo, orderId: widget.orderId, orderItemId: widget.orderItemId, eligibility: e),
    );
    if (sent == true && mounted) {
      setState(() => _reload++);
      showSnack(context, 'Đã gửi yêu cầu hoàn tiền. Admin sẽ xem xét sớm.');
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<(RefundEligibility, List<RefundRequest>)>(
      key: ValueKey(_reload),
      future: () async {
        final e = await _repo.eligibility(widget.orderId, widget.orderItemId);
        final mine = await _repo.mine();
        return (e, mine.where((r) => r.orderItemId == widget.orderItemId).toList());
      }(),
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const Padding(padding: EdgeInsets.only(top: 8), child: LinearProgressIndicator(minHeight: 2));
        }
        if (snap.hasError) return const SizedBox.shrink();
        final (e, history) = snap.data!;
        final latest = history.isEmpty ? null : history.first;
        return Container(
          margin: const EdgeInsets.only(top: 10),
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: AppColors.surfaceMuted, borderRadius: BorderRadius.circular(12)),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (latest != null) ...[
                Row(children: [
                  const Icon(Icons.currency_exchange, size: 18, color: AppColors.textSecondary),
                  const SizedBox(width: 6),
                  Text('Hoàn tiền: ', style: GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w600)),
                  StatusPill(label: latest.statusLabel, color: refundStatusColor(latest.status)),
                ]),
                const SizedBox(height: 6),
              ],
              if (e.eligible) ...[
                Text(
                  'Bạn có thể yêu cầu hoàn ${formatOrderVnd(e.amountVnd)} đến ${_date(e.deadline)} '
                  '(đã học ${e.progressPercent}%).',
                  style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary),
                ),
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  onPressed: () => _ask(e),
                  icon: const Icon(Icons.undo_rounded, size: 18),
                  label: const Text('Yêu cầu hoàn tiền'),
                ),
              ] else if (latest == null)
                Text(
                  'Không hoàn tiền được: ${e.reasonText}. '
                  'Điều kiện: trong 7 ngày kể từ khi thanh toán, học không quá 20% và chưa nộp bài tập.',
                  style: GoogleFonts.plusJakartaSans(fontSize: 12, color: AppColors.textSecondary),
                ),
            ],
          ),
        );
      },
    );
  }
}

class _RefundRequestDialog extends StatefulWidget {
  const _RefundRequestDialog(
      {required this.repo, required this.orderId, required this.orderItemId, required this.eligibility});

  final RefundRepository repo;
  final int orderId;
  final int orderItemId;
  final RefundEligibility eligibility;

  @override
  State<_RefundRequestDialog> createState() => _RefundRequestDialogState();
}

class _RefundRequestDialogState extends State<_RefundRequestDialog> {
  String _reason = refundReasons.keys.first;
  final _note = TextEditingController();
  bool _sending = false;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    setState(() => _sending = true);
    try {
      await widget.repo.request(
        orderId: widget.orderId,
        orderItemId: widget.orderItemId,
        reasonCode: _reason,
        note: _note.text.trim().isEmpty ? null : _note.text.trim(),
      );
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) {
        setState(() => _sending = false);
        showSnack(context, e.toString(), isError: true);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Yêu cầu hoàn tiền'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Số tiền hoàn: ${formatOrderVnd(widget.eligibility.amountVnd)}',
                style: GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            Text('Khi được hoàn tiền, bạn sẽ không còn quyền học khóa này.',
                style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary)),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              initialValue: _reason,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'Lý do'),
              items: [
                for (final e in refundReasons.entries) DropdownMenuItem(value: e.key, child: Text(e.value)),
              ],
              onChanged: (v) => setState(() => _reason = v ?? _reason),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _note,
              maxLines: 3,
              maxLength: 1000,
              decoration: const InputDecoration(labelText: 'Ghi chú (không bắt buộc)'),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: _sending ? null : () => Navigator.pop(context), child: const Text('Hủy')),
        FilledButton(
          onPressed: _sending ? null : _send,
          style: FilledButton.styleFrom(backgroundColor: AppColors.primary),
          child: Text(_sending ? 'Đang gửi…' : 'Gửi yêu cầu'),
        ),
      ],
    );
  }
}

// ======================================================================= học viên: danh sách

class MyRefundsScreen extends StatelessWidget {
  const MyRefundsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final repo = RefundRepository();
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(title: const Text('Yêu cầu hoàn tiền')),
      body: AsyncBody<List<RefundRequest>>(
        load: repo.mine,
        builder: (context, list, reload) => RefreshIndicator(
          onRefresh: reload,
          child: list.isEmpty
              ? ListView(children: [
                  const SizedBox(height: 100),
                  const Icon(Icons.receipt_long_outlined, size: 56, color: AppColors.textMuted),
                  const SizedBox(height: 12),
                  Text('Bạn chưa có yêu cầu hoàn tiền nào.\nGửi yêu cầu trong chi tiết đơn hàng.',
                      textAlign: TextAlign.center,
                      style: GoogleFonts.plusJakartaSans(color: AppColors.textSecondary)),
                ])
              : ListView(padding: const EdgeInsets.all(12), children: [for (final r in list) _RefundCard(r)]),
        ),
      ),
    );
  }
}

class _RefundCard extends StatelessWidget {
  const _RefundCard(this.r, {this.actions});

  final RefundRequest r;
  final Widget? actions;

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 10),
      shape: RoundedRectangleBorder(side: const BorderSide(color: AppColors.border), borderRadius: BorderRadius.circular(14)),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(
                child: Text(r.itemName.isEmpty ? 'Đơn #${r.orderId}' : r.itemName,
                    style: GoogleFonts.plusJakartaSans(fontSize: 14.5, fontWeight: FontWeight.w700)),
              ),
              StatusPill(label: r.statusLabel, color: refundStatusColor(r.status)),
            ]),
            const SizedBox(height: 6),
            Text('${formatOrderVnd(r.amountVnd)} • Đơn #${r.orderId} • ${refundReasons[r.reasonCode] ?? r.reasonCode}',
                style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary)),
            if (r.learnerEmail != null)
              Text('Học viên: ${r.learnerName ?? ''} (${r.learnerEmail})',
                  style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary)),
            if (r.note != null && r.note!.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text('“${r.note}”', style: GoogleFonts.plusJakartaSans(fontSize: 12.5, fontStyle: FontStyle.italic)),
              ),
            const SizedBox(height: 4),
            Text(
              'Gửi: ${_date(r.requestedAt)}${r.refundedAt != null ? ' • Hoàn tiền: ${_date(r.refundedAt)}' : ''}',
              style: GoogleFonts.plusJakartaSans(fontSize: 11.5, color: AppColors.textMuted),
            ),
            if (actions != null) Padding(padding: const EdgeInsets.only(top: 8), child: actions!),
          ],
        ),
      ),
    );
  }
}

// ======================================================================= admin

class AdminRefundsScreen extends StatefulWidget {
  const AdminRefundsScreen({super.key});

  @override
  State<AdminRefundsScreen> createState() => _AdminRefundsScreenState();
}

class _AdminRefundsScreenState extends State<AdminRefundsScreen> {
  final _repo = RefundRepository();
  int _reload = 0;
  String _filter = 'PENDING';
  bool _busy = false;

  Future<void> _run(Future<({RefundRequest refund, String message})> Function() action) async {
    setState(() => _busy = true);
    try {
      final result = await action();
      if (mounted) showSnack(context, result.message, isError: result.refund.status == 'FAILED');
    } catch (e) {
      if (mounted) showSnack(context, e.toString(), isError: true);
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _reload++;
        });
      }
    }
  }

  Future<String?> _input(String title, String label) => showDialog<String>(
        context: context,
        builder: (ctx) {
          final c = TextEditingController();
          return AlertDialog(
            title: Text(title),
            content: TextField(controller: c, autofocus: true, decoration: InputDecoration(labelText: label)),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Hủy')),
              FilledButton(
                onPressed: () => Navigator.pop(ctx, c.text.trim().isEmpty ? null : c.text.trim()),
                child: const Text('Xác nhận'),
              ),
            ],
          );
        },
      );

  Widget _actions(RefundRequest r) {
    final buttons = <Widget>[];
    if (r.status == 'PENDING') {
      buttons.add(FilledButton(
        onPressed: _busy ? null : () => _run(() => _repo.approve(r.id)),
        style: FilledButton.styleFrom(backgroundColor: AppColors.primary),
        child: const Text('Duyệt & hoàn tiền'),
      ));
      buttons.add(OutlinedButton(
        onPressed: _busy
            ? null
            : () async {
                final reason = await _input('Từ chối hoàn tiền', 'Lý do từ chối');
                if (reason != null) await _run(() => _repo.reject(r.id, reason));
              },
        child: const Text('Từ chối'),
      ));
    }
    if (r.status == 'FAILED') {
      buttons.add(OutlinedButton(
          onPressed: _busy ? null : () => _run(() => _repo.retry(r.id)), child: const Text('Thử lại qua VNPay')));
    }
    if (r.status == 'FAILED' || r.status == 'PROCESSING') {
      buttons.add(FilledButton(
        onPressed: _busy
            ? null
            : () async {
                final note = await _input('Đã hoàn tiền thủ công', 'Mã / ghi chú chuyển khoản');
                if (note != null) await _run(() => _repo.confirmManual(r.id, note));
              },
        style: FilledButton.styleFrom(backgroundColor: AppColors.accentPeach),
        child: const Text('Đã hoàn thủ công'),
      ));
    }
    // VNPay chưa trả kết quả rõ: admin kiểm tra trên cổng VNPay rồi chốt
    if (r.status == 'VERIFYING') {
      buttons.add(FilledButton(
        onPressed: _busy
            ? null
            : () async {
                final note = await _input('VNPay đã hoàn tiền', 'Ghi chú (mã giao dịch hoàn trên cổng VNPay)');
                if (note != null) await _run(() => _repo.resolve(r.id, refunded: true, note: note));
              },
        style: FilledButton.styleFrom(backgroundColor: AppColors.primary),
        child: const Text('VNPay đã hoàn'),
      ));
      buttons.add(OutlinedButton(
        onPressed: _busy
            ? null
            : () async {
                final note = await _input('VNPay chưa hoàn tiền', 'Ghi chú kết quả kiểm tra');
                if (note != null) await _run(() => _repo.resolve(r.id, refunded: false, note: note));
              },
        child: const Text('VNPay chưa hoàn'),
      ));
    }
    return buttons.isEmpty ? const SizedBox.shrink() : Wrap(spacing: 8, runSpacing: 6, children: buttons);
  }

  @override
  Widget build(BuildContext context) {
    const filters = {'PENDING': 'Chờ duyệt', 'ACTION': 'Cần xử lý', 'ALL': 'Tất cả'};
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(title: const Text('Duyệt hoàn tiền')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 4),
            child: Wrap(spacing: 8, children: [
              for (final f in filters.entries)
                ChoiceChip(label: Text(f.value), selected: _filter == f.key, onSelected: (_) => setState(() => _filter = f.key)),
            ]),
          ),
          Expanded(
            child: AsyncBody<List<RefundRequest>>(
              key: ValueKey('refunds-$_reload'),
              load: _repo.adminAll,
              builder: (context, all, reload) {
                final list = all.where((r) => switch (_filter) {
                      'PENDING' => r.status == 'PENDING',
                      'ACTION' => r.status == 'FAILED' || r.status == 'VERIFYING' || r.status == 'PROCESSING',
                      _ => true,
                    }).toList();
                return RefreshIndicator(
                  onRefresh: reload,
                  child: list.isEmpty
                      ? ListView(children: [
                          const SizedBox(height: 100),
                          Text('Không có yêu cầu nào', textAlign: TextAlign.center,
                              style: GoogleFonts.plusJakartaSans(color: AppColors.textSecondary)),
                        ])
                      : ListView(
                          padding: const EdgeInsets.all(12),
                          children: [for (final r in list) _RefundCard(r, actions: _actions(r))],
                        ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
