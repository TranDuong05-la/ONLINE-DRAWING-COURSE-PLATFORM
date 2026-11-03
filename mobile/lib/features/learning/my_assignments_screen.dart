import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';

import '../../core/constants/app_colors.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import 'assignment_screen.dart';
import 'learning_widgets.dart';

/// "Bài tập của tôi": mọi bài tập của các khóa đang học, chia 3 nhóm:
/// - Cần làm: chưa nộp hoặc bị yêu cầu sửa
/// - Chờ chấm: đã nộp, admin chưa chấm
/// - Đã chấm: đã được duyệt đạt
class MyAssignmentsScreen extends StatefulWidget {
  const MyAssignmentsScreen({super.key});

  @override
  State<MyAssignmentsScreen> createState() => _MyAssignmentsScreenState();
}

class _MyAssignmentsScreenState extends State<MyAssignmentsScreen> {
  final _repo = LearningRepository();
  int _reloadKey = 0;

  Future<void> _open(MyAssignment a) async {
    await Navigator.of(context)
        .push(MaterialPageRoute(builder: (_) => AssignmentScreen(lessonId: a.lessonId, lessonTitle: a.lessonTitle)));
    if (mounted) setState(() => _reloadKey++);
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        backgroundColor: Colors.white,
        appBar: AppBar(
          title: const Text('Bài tập của tôi'),
          bottom: const TabBar(tabs: [Tab(text: 'Cần làm'), Tab(text: 'Chờ chấm'), Tab(text: 'Đã chấm')]),
        ),
        body: AsyncBody<List<MyAssignment>>(
          key: ValueKey(_reloadKey),
          load: _repo.getMyAssignments,
          builder: (context, all, reload) {
            final todo = all.where((a) => a.status == 'NOT_SUBMITTED' || a.status == 'NEEDS_REVISION').toList();
            final pending = all.where((a) => a.status == 'PENDING').toList();
            final graded = all.where((a) => a.status == 'APPROVED').toList();
            return TabBarView(children: [
              _list(todo, reload, 'Không còn bài tập nào cần làm'),
              _list(pending, reload, 'Không có bài nào đang chờ chấm'),
              _list(graded, reload, 'Chưa có bài nào được chấm đạt'),
            ]);
          },
        ),
      ),
    );
  }

  Widget _list(List<MyAssignment> items, Future<void> Function() reload, String empty) {
    return RefreshIndicator(
      onRefresh: reload,
      child: items.isEmpty
          ? ListView(children: [
              const SizedBox(height: 120),
              const Icon(Icons.assignment_outlined, size: 56, color: AppColors.textMuted),
              const SizedBox(height: 12),
              Text(empty, textAlign: TextAlign.center, style: GoogleFonts.plusJakartaSans(color: AppColors.textSecondary)),
            ])
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [for (final a in items) _card(a)],
            ),
    );
  }

  Widget _card(MyAssignment a) {
    final date = a.submittedAt == null ? null : DateFormat('dd/MM/yyyy HH:mm').format(a.submittedAt!);
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      elevation: 0,
      shape: RoundedRectangleBorder(
          side: const BorderSide(color: AppColors.border), borderRadius: BorderRadius.circular(14)),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () => _open(a),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(a.title,
                        style: GoogleFonts.plusJakartaSans(fontSize: 15, fontWeight: FontWeight.w700)),
                  ),
                  StatusPill(label: assignmentStatusLabel(a.status), color: assignmentStatusColor(a.status)),
                ],
              ),
              const SizedBox(height: 4),
              Text('${a.courseTitle} • ${a.lessonTitle}',
                  style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary)),
              const SizedBox(height: 6),
              Wrap(spacing: 8, runSpacing: 4, children: [
                if (a.required) const StatusPill(label: 'Bắt buộc', color: Color(0xFF8E7CC3)),
                if (date != null) Text('Nộp lúc $date', style: GoogleFonts.plusJakartaSans(fontSize: 12)),
                if (a.score != null)
                  Text('Điểm: ${a.score}', style: GoogleFonts.plusJakartaSans(fontSize: 12, fontWeight: FontWeight.w700)),
              ]),
              if (a.feedback != null) ...[
                const SizedBox(height: 6),
                Text('Nhận xét: ${a.feedback}', style: GoogleFonts.plusJakartaSans(fontSize: 12.5)),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
