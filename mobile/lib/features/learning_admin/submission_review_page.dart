import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';

import '../../core/constants/app_colors.dart';
import '../../core/constants/staff_area.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import '../learning/learning_widgets.dart';

/// Chi tiết một bài nộp cho admin: đề bài, ảnh bài làm (tải kèm header đăng nhập), ghi chú, lịch sử các lần nộp,
/// và form chấm: Duyệt đạt hoặc Yêu cầu sửa (bắt buộc nhập phản hồi). Chỉ chấm được bài đang "chờ chấm";
/// nếu admin khác đã chấm trước, server trả 409 và màn hình tải lại.
class SubmissionReviewPage extends StatefulWidget {
  const SubmissionReviewPage({super.key, required this.submissionId});

  final int submissionId;

  @override
  State<SubmissionReviewPage> createState() => _SubmissionReviewPageState();
}

class _SubmissionReviewPageState extends State<SubmissionReviewPage> {
  final _repo = LearningRepository();
  final _feedback = TextEditingController();
  final _score = TextEditingController();
  Map<String, String> _headers = const {};
  int _reloadKey = 0;
  bool _busy = false;
  bool _changed = false;

  Future<AdminSubmission> _load() async {
    _headers = await _repo.authHeaders();
    return _repo.adminSubmission(widget.submissionId);
  }

  Future<void> _review(String decision) async {
    final feedback = _feedback.text.trim();
    if (decision == 'NEEDS_REVISION' && feedback.isEmpty) {
      showSnack(context, 'Vui lòng nhập phản hồi để học viên biết cần sửa gì.', isError: true);
      return;
    }
    double? score;
    if (_score.text.trim().isNotEmpty) {
      score = double.tryParse(_score.text.trim().replaceAll(',', '.'));
      if (score == null || score < 0 || score > 100) {
        showSnack(context, 'Điểm phải là số từ 0 đến 100.', isError: true);
        return;
      }
    }
    setState(() => _busy = true);
    try {
      await _repo.review(widget.submissionId,
          decision: decision, feedback: feedback.isEmpty ? null : feedback, score: score);
      _changed = true;
      if (!mounted) return;
      showSnack(context, decision == 'APPROVED' ? 'Đã duyệt bài đạt' : 'Đã yêu cầu học viên sửa bài');
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      showSnack(context, e.toString(), isError: true);
      setState(() => _reloadKey++);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    _feedback.dispose();
    _score.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) Navigator.of(context).pop(_changed);
      },
      child: Scaffold(
        backgroundColor: Colors.white,
        appBar: AppBar(title: Text('Bài nộp #${widget.submissionId}')),
        body: AsyncBody<AdminSubmission>(
          key: ValueKey(_reloadKey),
          load: _load,
          builder: (context, s, reload) => ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(s.assignmentTitle ?? 'Bài tập',
                  style: GoogleFonts.plusJakartaSans(fontSize: 19, fontWeight: FontWeight.w800, color: AppColors.primaryDark)),
              const SizedBox(height: 4),
              Text('${s.courseTitle ?? ''} • ${s.lessonTitle ?? ''}',
                  style: GoogleFonts.plusJakartaSans(fontSize: 13, color: AppColors.textSecondary)),
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 6, children: [
                StatusPill(label: assignmentStatusLabel(s.status), color: assignmentStatusColor(s.status)),
                StatusPill(label: 'Lần nộp ${s.attemptNo}', color: AppColors.primary),
              ]),
              const SizedBox(height: 12),
              _info('Học viên', '${s.learnerName ?? ''}${s.learnerEmail == null ? '' : ' (${s.learnerEmail})'}'),
              if (s.assignmentInstructions != null) _info('Yêu cầu', s.assignmentInstructions!),
              if (s.note != null) _info('Ghi chú của học viên', s.note!),
              const SizedBox(height: 12),
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: InteractiveViewer(
                  child: Image.network(
                    // Server trả link ảnh của admin; giảng viên tải qua /api/instructor (chỉ khóa được phân công)
                    _repo.url(StaffArea.instructor
                        ? s.imagePath.replaceFirst('/api/admin/', '/api/instructor/')
                        : s.imagePath),
                    headers: _headers,
                    fit: BoxFit.contain,
                    errorBuilder: (_, _, _) =>
                        const SizedBox(height: 80, child: Center(child: Text('Không tải được ảnh'))),
                  ),
                ),
              ),
              const SizedBox(height: 18),
              if (s.status == 'PENDING') _reviewForm() else _result(s),
              if (s.history.length > 1) ...[
                const SizedBox(height: 22),
                Text('Lịch sử các lần nộp',
                    style: GoogleFonts.plusJakartaSans(fontSize: 15, fontWeight: FontWeight.w700)),
                for (final h in s.history) _historyRow(h),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _info(String label, String value) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: RichText(
          text: TextSpan(
            style: GoogleFonts.plusJakartaSans(fontSize: 13.5, color: AppColors.textPrimary, height: 1.4),
            children: [
              TextSpan(text: '$label: ', style: const TextStyle(fontWeight: FontWeight.w700)),
              TextSpan(text: value),
            ],
          ),
        ),
      );

  Widget _reviewForm() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _feedback,
          maxLines: 4,
          maxLength: 2000,
          decoration: InputDecoration(
            labelText: 'Phản hồi cho học viên (bắt buộc khi yêu cầu sửa)',
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
          ),
        ),
        TextField(
          controller: _score,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
            labelText: 'Điểm (0-100, không bắt buộc)',
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
          ),
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _busy ? null : () => _review('NEEDS_REVISION'),
                icon: const Icon(Icons.edit_note_rounded),
                label: const Text('Yêu cầu sửa'),
                style: OutlinedButton.styleFrom(
                    foregroundColor: AppColors.warning, padding: const EdgeInsets.symmetric(vertical: 14)),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: ElevatedButton.icon(
                onPressed: _busy ? null : () => _review('APPROVED'),
                icon: const Icon(Icons.check_circle_outline),
                label: const Text('Duyệt đạt'),
                style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.success,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 14)),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _result(AdminSubmission s) {
    final date = s.reviewedAt == null ? '' : DateFormat('dd/MM/yyyy HH:mm').format(s.reviewedAt!);
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: AppColors.accentYellowLight, borderRadius: BorderRadius.circular(12)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Đã chấm $date', style: GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w700)),
          if (s.score != null) Text('Điểm: ${s.score}'),
          if (s.feedback != null) Text('Phản hồi: ${s.feedback}'),
        ],
      ),
    );
  }

  Widget _historyRow(Submission h) {
    final date = h.submittedAt == null ? '' : DateFormat('dd/MM HH:mm').format(h.submittedAt!);
    return ListTile(
      contentPadding: EdgeInsets.zero,
      dense: true,
      title: Text('Lần ${h.attemptNo} • $date'),
      subtitle: h.feedback == null ? null : Text('Phản hồi: ${h.feedback}'),
      trailing: StatusPill(label: assignmentStatusLabel(h.status), color: assignmentStatusColor(h.status)),
    );
  }
}
