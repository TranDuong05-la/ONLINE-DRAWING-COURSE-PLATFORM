import 'dart:io';

import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';

import '../../core/constants/app_colors.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import 'learning_widgets.dart';

/// Assignment of a lesson: instructions, image submission (JPEG/PNG, max 5MB) and submission history
/// with the reviewer's score and feedback. Whether a new submission is allowed is decided by the server.
class AssignmentScreen extends StatefulWidget {
  const AssignmentScreen({super.key, required this.lessonId, this.lessonTitle});

  final int lessonId;
  final String? lessonTitle;

  @override
  State<AssignmentScreen> createState() => _AssignmentScreenState();
}

class _AssignmentScreenState extends State<AssignmentScreen> {
  static const _maxBytes = 5 * 1024 * 1024;

  final _repo = LearningRepository();
  final _note = TextEditingController();
  XFile? _picked;
  String? _mime;
  bool _busy = false;
  int _reloadKey = 0;
  Map<String, String> _imageHeaders = const {};

  /// Loads the assignment, then the login header used to show the private submission images.
  Future<AssignmentInfo> _load() async {
    final a = await _repo.getAssignment(widget.lessonId);
    _imageHeaders = await _repo.authHeaders();
    return a;
  }

  Future<void> _pick(ImageSource source) async {
    final file = await ImagePicker().pickImage(source: source, maxWidth: 2400, imageQuality: 85);
    if (file == null) return;
    // Trên web file.path là link "blob:..." không có đuôi: xét tên file và loại nội dung thay vì đường dẫn
    final lower = file.name.toLowerCase();
    final type = file.mimeType?.toLowerCase();
    final mime = (lower.endsWith('.png') || type == 'image/png')
        ? 'image/png'
        : (lower.endsWith('.jpg') || lower.endsWith('.jpeg') || type == 'image/jpeg')
            ? 'image/jpeg'
            : null;
    if (mime == null) {
      if (mounted) showSnack(context, 'Chỉ chấp nhận ảnh JPEG hoặc PNG.', isError: true);
      return;
    }
    if (await file.length() > _maxBytes) {
      if (mounted) showSnack(context, 'Ảnh vượt quá 5MB, vui lòng chọn ảnh nhỏ hơn.', isError: true);
      return;
    }
    setState(() {
      _picked = file;
      _mime = mime;
    });
  }

  Future<void> _submit() async {
    final file = _picked;
    if (file == null || _mime == null) return;
    setState(() => _busy = true);
    try {
      await _repo.submitAssignment(widget.lessonId, file: file, mimeType: _mime!, note: _note.text);
      if (!mounted) return;
      showSnack(context, 'Đã nộp bài. Giảng viên sẽ chấm và phản hồi sớm.');
      setState(() {
        _picked = null;
        _note.clear();
        _reloadKey++;
      });
    } catch (e) {
      if (mounted) {
        showSnack(context, e.toString(), isError: true);
        setState(() => _reloadKey++); // state may have changed (e.g. already pending)
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(title: const Text('Bài tập'), elevation: 0),
      body: AsyncBody<AssignmentInfo>(
        key: ValueKey(_reloadKey),
        load: _load,
        builder: (context, a, reload) => RefreshIndicator(
          onRefresh: reload,
          color: AppColors.primary,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(a.title,
                  style: GoogleFonts.plusJakartaSans(fontSize: 20, fontWeight: FontWeight.w800, color: AppColors.primaryDark)),
              const SizedBox(height: 2),
              Text(widget.lessonTitle ?? a.lessonTitle,
                  style: GoogleFonts.plusJakartaSans(fontSize: 13, color: AppColors.textSecondary)),
              const SizedBox(height: 10),
              Wrap(spacing: 8, children: [
                StatusPill(
                    label: a.required ? 'Bắt buộc' : 'Tùy chọn',
                    color: a.required ? const Color(0xFF8E7CC3) : AppColors.textSecondary),
                StatusPill(
                    label: assignmentStatusLabel(a.latestStatus),
                    color: assignmentStatusColor(a.latestStatus)),
              ]),
              if (a.instructions != null) ...[
                const SizedBox(height: 16),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(color: AppColors.primaryMintLight, borderRadius: BorderRadius.circular(14)),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Yêu cầu',
                          style: GoogleFonts.plusJakartaSans(fontSize: 14, fontWeight: FontWeight.w700)),
                      const SizedBox(height: 4),
                      Text(a.instructions!, style: GoogleFonts.plusJakartaSans(fontSize: 13.5, height: 1.4)),
                    ],
                  ),
                ),
              ],
              const SizedBox(height: 18),
              if (a.canSubmit) _form() else _lockedMessage(a),
              if (a.submissions.isNotEmpty) ...[
                const SizedBox(height: 24),
                Text('Lịch sử nộp bài',
                    style: GoogleFonts.plusJakartaSans(fontSize: 15, fontWeight: FontWeight.w700)),
                for (final s in a.submissions) _submissionCard(s),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _lockedMessage(AssignmentInfo a) {
    final approved = a.latestStatus == 'APPROVED';
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: approved ? AppColors.successLight : AppColors.surfaceMuted,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          Icon(approved ? Icons.verified : Icons.hourglass_top_rounded,
              color: approved ? AppColors.success : AppColors.info),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              approved
                  ? 'Bài tập của bạn đã được chấm đạt.'
                  : 'Bạn đã nộp bài, đang chờ giảng viên chấm. Nếu được yêu cầu chỉnh sửa, bạn có thể nộp lại.',
              style: GoogleFonts.plusJakartaSans(fontSize: 13.5),
            ),
          ),
        ],
      ),
    );
  }

  Widget _form() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Nộp bài', style: GoogleFonts.plusJakartaSans(fontSize: 15, fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        if (_picked != null) ...[
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: Image.file(File(_picked!.path), height: 220, width: double.infinity, fit: BoxFit.cover),
          ),
          const SizedBox(height: 8),
        ],
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _busy ? null : () => _pick(ImageSource.gallery),
                icon: const Icon(Icons.photo_library_outlined),
                label: Text(_picked == null ? 'Chọn ảnh' : 'Chọn ảnh khác'),
                style: OutlinedButton.styleFrom(foregroundColor: AppColors.primary),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _busy ? null : () => _pick(ImageSource.camera),
                icon: const Icon(Icons.photo_camera_outlined),
                label: const Text('Chụp ảnh'),
                style: OutlinedButton.styleFrom(foregroundColor: AppColors.primary),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _note,
          maxLines: 3,
          maxLength: 2000,
          decoration: InputDecoration(
            labelText: 'Ghi chú cho giảng viên (không bắt buộc)',
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
          ),
        ),
        SizedBox(
          width: double.infinity,
          child: ElevatedButton.icon(
            onPressed: (_picked == null || _busy) ? null : _submit,
            icon: _busy
                ? const SizedBox(
                    height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                : const Icon(Icons.upload_rounded),
            label: const Text('Nộp bài'),
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.primary,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 14),
            ),
          ),
        ),
      ],
    );
  }

  Widget _submissionCard(Submission s) {
    final date = s.submittedAt != null ? DateFormat('dd/MM/yyyy HH:mm').format(s.submittedAt!) : '';
    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(border: Border.all(color: AppColors.border), borderRadius: BorderRadius.circular(14)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text('Lần ${s.attemptNo} • $date',
                    style: GoogleFonts.plusJakartaSans(fontSize: 13, fontWeight: FontWeight.w600)),
              ),
              StatusPill(label: assignmentStatusLabel(s.status), color: assignmentStatusColor(s.status)),
            ],
          ),
          const SizedBox(height: 8),
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: Image.network(
              _repo.url(s.imagePath),
              headers: _imageHeaders,
              height: 170,
              width: double.infinity,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) =>
                  const SizedBox(height: 60, child: Center(child: Text('Không tải được ảnh'))),
            ),
          ),
          if (s.note != null) ...[
            const SizedBox(height: 8),
            Text('Ghi chú: ${s.note}', style: GoogleFonts.plusJakartaSans(fontSize: 13)),
          ],
          if (s.score != null || s.feedback != null) ...[
            const SizedBox(height: 8),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(color: AppColors.accentYellowLight, borderRadius: BorderRadius.circular(10)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (s.score != null)
                    Text('Điểm: ${s.score}',
                        style: GoogleFonts.plusJakartaSans(fontSize: 14, fontWeight: FontWeight.w800)),
                  if (s.feedback != null)
                    Text('Nhận xét: ${s.feedback}', style: GoogleFonts.plusJakartaSans(fontSize: 13)),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }
}
