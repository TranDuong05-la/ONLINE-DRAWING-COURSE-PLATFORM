import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';

import '../../core/constants/app_colors.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import '../learning/learning_widgets.dart';
import 'submission_review_page.dart';

/// Khu admin của phần C (route /admin/learning, chỉ ADMIN; backend cũng chặn /api/admin/**):
/// - "Chấm bài": danh sách bài nộp, lọc theo trạng thái / khóa, mở chi tiết để duyệt hoặc yêu cầu sửa.
/// - "Đề bài tập": chọn khóa → bài học → soạn đề (bắt buộc / không bắt buộc, ẩn đề).
class LearningAdminHomePage extends StatelessWidget {
  const LearningAdminHomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        backgroundColor: Colors.white,
        appBar: AppBar(
          title: const Text('Chấm bài & đề bài tập'),
          bottom: const TabBar(tabs: [Tab(text: 'Chấm bài'), Tab(text: 'Đề bài tập')]),
        ),
        body: const TabBarView(children: [_SubmissionsTab(), _AssignmentsTab()]),
      ),
    );
  }
}

// ---------------------------------------------------------------- chấm bài

class _SubmissionsTab extends StatefulWidget {
  const _SubmissionsTab();

  @override
  State<_SubmissionsTab> createState() => _SubmissionsTabState();
}

class _SubmissionsTabState extends State<_SubmissionsTab> with AutomaticKeepAliveClientMixin {
  final _repo = LearningRepository();
  String? _status = 'PENDING';
  int? _courseId;
  List<CourseOption> _courses = const [];
  int _reloadKey = 0;

  static const _statuses = <String?, String>{
    'PENDING': 'Chờ chấm',
    'NEEDS_REVISION': 'Cần sửa',
    'APPROVED': 'Đạt',
    null: 'Tất cả',
  };

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _repo.adminSubmissionCourses().then((c) {
      if (mounted) setState(() => _courses = c);
    }).catchError((_) {});
  }

  Future<(SubmissionSummary, SubmissionPage)> _load() async {
    final results = await Future.wait([
      _repo.adminSummary(),
      _repo.adminSubmissions(courseId: _courseId, status: _status, size: 50),
    ]);
    return (results[0] as SubmissionSummary, results[1] as SubmissionPage);
  }

  Future<void> _open(AdminSubmission s) async {
    final changed = await Navigator.of(context)
        .push<bool>(MaterialPageRoute(builder: (_) => SubmissionReviewPage(submissionId: s.id)));
    if (changed == true && mounted) setState(() => _reloadKey++);
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: Row(
            children: [
              Expanded(
                child: DropdownButtonFormField<int?>(
                  initialValue: _courseId,
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: 'Khóa học', isDense: true),
                  items: [
                    const DropdownMenuItem(value: null, child: Text('Tất cả khóa')),
                    for (final c in _courses)
                      DropdownMenuItem(value: c.id, child: Text(c.title, overflow: TextOverflow.ellipsis)),
                  ],
                  onChanged: (v) => setState(() {
                    _courseId = v;
                    _reloadKey++;
                  }),
                ),
              ),
            ],
          ),
        ),
        SizedBox(
          height: 48,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            children: [
              for (final e in _statuses.entries)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: ChoiceChip(
                    label: Text(e.value),
                    selected: _status == e.key,
                    onSelected: (_) => setState(() {
                      _status = e.key;
                      _reloadKey++;
                    }),
                  ),
                ),
            ],
          ),
        ),
        Expanded(
          child: AsyncBody<(SubmissionSummary, SubmissionPage)>(
            key: ValueKey(_reloadKey),
            load: _load,
            builder: (context, data, reload) {
              final (summary, page) = data;
              return RefreshIndicator(
                onRefresh: reload,
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
                  children: [
                    Wrap(spacing: 8, runSpacing: 6, children: [
                      StatusPill(label: 'Chờ chấm: ${summary.pending}', color: AppColors.info),
                      StatusPill(label: 'Cần sửa: ${summary.needsRevision}', color: AppColors.warning),
                      StatusPill(label: 'Đạt: ${summary.approved}', color: AppColors.success),
                    ]),
                    const SizedBox(height: 12),
                    if (page.items.isEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 60),
                        child: Text('Không có bài nộp nào', textAlign: TextAlign.center,
                            style: GoogleFonts.plusJakartaSans(color: AppColors.textSecondary)),
                      ),
                    for (final s in page.items) _row(s),
                  ],
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _row(AdminSubmission s) {
    final date = s.submittedAt == null ? '' : DateFormat('dd/MM HH:mm').format(s.submittedAt!);
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      elevation: 0,
      shape: RoundedRectangleBorder(
          side: const BorderSide(color: AppColors.border), borderRadius: BorderRadius.circular(14)),
      child: ListTile(
        onTap: () => _open(s),
        title: Text(s.assignmentTitle ?? s.lessonTitle ?? 'Bài nộp #${s.id}',
            style: GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w700, fontSize: 14)),
        subtitle: Text('${s.learnerName ?? 'Học viên'} • ${s.courseTitle ?? ''}\nLần ${s.attemptNo} • $date',
            style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary)),
        isThreeLine: true,
        trailing: StatusPill(label: assignmentStatusLabel(s.status), color: assignmentStatusColor(s.status)),
      ),
    );
  }
}

// ---------------------------------------------------------------- đề bài tập

class _AssignmentsTab extends StatefulWidget {
  const _AssignmentsTab();

  @override
  State<_AssignmentsTab> createState() => _AssignmentsTabState();
}

class _AssignmentsTabState extends State<_AssignmentsTab> with AutomaticKeepAliveClientMixin {
  final _repo = LearningRepository();
  int? _courseId;
  int _reloadKey = 0;

  @override
  bool get wantKeepAlive => true;

  Future<void> _edit(LessonAssignmentAdmin l) async {
    final saved = await showDialog<bool>(context: context, builder: (_) => _AssignmentDialog(lesson: l, repo: _repo));
    if (saved == true && mounted) {
      setState(() => _reloadKey++);
      showSnack(context, 'Đã lưu đề bài tập');
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return AsyncBody<List<CourseOption>>(
      load: _repo.adminAllCourses,
      builder: (context, courses, _) {
        if (courses.isEmpty) {
          return const Center(child: Text('Chưa có khóa học nào'));
        }
        final selected = _courseId ?? courses.first.id;
        return Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: DropdownButtonFormField<int>(
                initialValue: selected,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Khóa học', isDense: true),
                items: [
                  for (final c in courses)
                    DropdownMenuItem(value: c.id, child: Text(c.title, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setState(() => _courseId = v),
              ),
            ),
            Expanded(
              child: AsyncBody<List<LessonAssignmentAdmin>>(
                key: ValueKey('$selected-$_reloadKey'),
                load: () => _repo.adminLessonAssignments(selected),
                builder: (context, lessons, reload) => RefreshIndicator(
                  onRefresh: reload,
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                    children: [
                      if (lessons.isEmpty) const Padding(padding: EdgeInsets.all(24), child: Text('Khóa chưa có bài học')),
                      for (final l in lessons)
                        Card(
                          margin: const EdgeInsets.only(bottom: 10),
                          elevation: 0,
                          shape: RoundedRectangleBorder(
                              side: const BorderSide(color: AppColors.border), borderRadius: BorderRadius.circular(14)),
                          child: ListTile(
                            onTap: () => _edit(l),
                            title: Text(l.lessonTitle,
                                style: GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w700, fontSize: 14)),
                            subtitle: Text(
                              '${l.moduleTitle}${l.lessonHidden ? ' • bài đang ẩn' : ''}\n'
                              '${l.assignmentId == null ? 'Chưa có đề bài tập' : l.title}',
                              style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary),
                            ),
                            isThreeLine: true,
                            trailing: l.assignmentId == null
                                ? const Icon(Icons.add_circle_outline, color: AppColors.primary)
                                : StatusPill(
                                    label: !l.active ? 'Đã ẩn' : (l.required ? 'Bắt buộc' : 'Tùy chọn'),
                                    color: !l.active
                                        ? AppColors.textSecondary
                                        : (l.required ? const Color(0xFF8E7CC3) : AppColors.info),
                                  ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

class _AssignmentDialog extends StatefulWidget {
  const _AssignmentDialog({required this.lesson, required this.repo});

  final LessonAssignmentAdmin lesson;
  final LearningRepository repo;

  @override
  State<_AssignmentDialog> createState() => _AssignmentDialogState();
}

class _AssignmentDialogState extends State<_AssignmentDialog> {
  late final _title = TextEditingController(text: widget.lesson.title ?? '');
  late final _instructions = TextEditingController(text: widget.lesson.instructions ?? '');
  late bool _required = widget.lesson.assignmentId == null ? true : widget.lesson.required;
  late bool _active = widget.lesson.assignmentId == null ? true : widget.lesson.active;
  bool _busy = false;
  String? _error;

  Future<void> _save() async {
    if (_title.text.trim().isEmpty) {
      setState(() => _error = 'Vui lòng nhập tiêu đề đề bài');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.repo.saveAssignment(widget.lesson.lessonId,
          title: _title.text.trim(), instructions: _instructions.text, required: _required, active: _active);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    _title.dispose();
    _instructions.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Đề bài: ${widget.lesson.lessonTitle}'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _title,
              maxLength: 200,
              decoration: const InputDecoration(labelText: 'Tiêu đề'),
            ),
            TextField(
              controller: _instructions,
              maxLines: 5,
              maxLength: 5000,
              decoration: const InputDecoration(labelText: 'Yêu cầu / hướng dẫn'),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Bắt buộc để hoàn thành khóa'),
              value: _required,
              onChanged: (v) => setState(() => _required = v),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Hiển thị cho học viên'),
              subtitle: const Text('Tắt để ẩn đề (lịch sử bài nộp vẫn giữ)'),
              value: _active,
              onChanged: (v) => setState(() => _active = v),
            ),
            if (_error != null) Text(_error!, style: const TextStyle(color: AppColors.error)),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(false), child: const Text('Hủy')),
        ElevatedButton(onPressed: _busy ? null : _save, child: const Text('Lưu')),
      ],
    );
  }
}
