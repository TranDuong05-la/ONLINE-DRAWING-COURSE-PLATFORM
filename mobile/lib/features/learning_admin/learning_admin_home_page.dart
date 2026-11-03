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
/// - "Quyền học": chọn khóa → danh sách học viên; cấp quyền thủ công theo email, thu hồi quyền cấp tay.
class LearningAdminHomePage extends StatelessWidget {
  const LearningAdminHomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        backgroundColor: Colors.white,
        appBar: AppBar(
          title: const Text('Chấm bài & đề bài tập'),
          bottom: const TabBar(tabs: [Tab(text: 'Chấm bài'), Tab(text: 'Đề bài tập'), Tab(text: 'Quyền học')]),
        ),
        body: const TabBarView(children: [_SubmissionsTab(), _AssignmentsTab(), _EnrollmentsTab()]),
      ),
    );
  }
}

// ---------------------------------------------------------------- quyền học (cấp tay)

class _EnrollmentsTab extends StatefulWidget {
  const _EnrollmentsTab();

  @override
  State<_EnrollmentsTab> createState() => _EnrollmentsTabState();
}

class _EnrollmentsTabState extends State<_EnrollmentsTab> with AutomaticKeepAliveClientMixin {
  final _repo = LearningRepository();
  int? _courseId;
  int _reloadKey = 0;

  @override
  bool get wantKeepAlive => true;

  Future<void> _grant(int courseId) async {
    final email = await showDialog<String>(context: context, builder: (_) => const _GrantDialog());
    if (email == null || !mounted) return;
    try {
      await _repo.grantEnrollment(courseId, email);
      if (!mounted) return;
      setState(() => _reloadKey++);
      showSnack(context, 'Đã cấp quyền học cho $email');
    } catch (e) {
      if (mounted) showSnack(context, e.toString(), isError: true);
    }
  }

  Future<void> _revoke(AdminEnrollment e) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Thu hồi quyền học?'),
        content: Text('${e.learnerName} (${e.learnerEmail}) sẽ không vào học khóa này được nữa. '
            'Tiến độ học vẫn được giữ nếu cấp lại sau.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Hủy')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: AppColors.error),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Thu hồi'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await _repo.revokeEnrollment(e.id, reason: 'Admin thu hồi');
      if (!mounted) return;
      setState(() => _reloadKey++);
      showSnack(context, 'Đã thu hồi quyền học');
    } catch (err) {
      if (mounted) showSnack(context, err.toString(), isError: true);
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
              child: Row(
                children: [
                  Expanded(
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
                  const SizedBox(width: 8),
                  FilledButton.icon(
                    onPressed: () => _grant(selected),
                    icon: const Icon(Icons.person_add_alt_1, size: 18),
                    label: const Text('Cấp quyền'),
                    style: FilledButton.styleFrom(backgroundColor: AppColors.primary),
                  ),
                ],
              ),
            ),
            Expanded(
              child: AsyncBody<List<AdminEnrollment>>(
                key: ValueKey('enroll-$selected-$_reloadKey'),
                load: () => _repo.adminEnrollments(selected),
                builder: (context, list, reload) => RefreshIndicator(
                  onRefresh: reload,
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                    children: [
                      if (list.isEmpty)
                        const Padding(padding: EdgeInsets.all(24), child: Text('Khóa chưa có học viên nào')),
                      for (final e in list) _tile(e),
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

  Widget _tile(AdminEnrollment e) {
    final date = e.enrolledAt == null ? '' : DateFormat('dd/MM/yyyy').format(e.enrolledAt!);
    final (label, color) = !e.active
        ? ('Đã thu hồi', AppColors.textSecondary)
        : e.completed
            ? ('Hoàn thành', AppColors.success)
            : ('Đang học', AppColors.primary);
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      elevation: 0,
      shape: RoundedRectangleBorder(side: const BorderSide(color: AppColors.border), borderRadius: BorderRadius.circular(14)),
      child: ListTile(
        title: Text(e.learnerName.isEmpty ? e.learnerEmail : e.learnerName,
            style: GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w700, fontSize: 14)),
        subtitle: Text(
          '${e.learnerEmail}\n${e.manual ? 'Admin cấp tay' : 'Mua qua đơn hàng'} • $date'
          '${!e.active && e.revokeReason != null ? ' • ${e.revokeReason}' : ''}',
          style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary),
        ),
        isThreeLine: true,
        trailing: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            StatusPill(label: label, color: color),
            if (e.active && e.manual)
              InkWell(
                onTap: () => _revoke(e),
                child: Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text('Thu hồi',
                      style: GoogleFonts.plusJakartaSans(
                          fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.error)),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _GrantDialog extends StatefulWidget {
  const _GrantDialog();

  @override
  State<_GrantDialog> createState() => _GrantDialogState();
}

class _GrantDialogState extends State<_GrantDialog> {
  final _email = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  void _submit() {
    final v = _email.text.trim();
    if (!RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(v)) {
      setState(() => _error = 'Email không hợp lệ');
      return;
    }
    Navigator.pop(context, v);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Cấp quyền học thủ công'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Học viên được học ngay, không cần thanh toán (ví dụ: học bổng, hỗ trợ).',
              style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary)),
          const SizedBox(height: 12),
          TextField(
            controller: _email,
            autofocus: true,
            keyboardType: TextInputType.emailAddress,
            decoration: InputDecoration(labelText: 'Email học viên', errorText: _error),
            onSubmitted: (_) => _submit(),
          ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Hủy')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: AppColors.primary),
          onPressed: _submit,
          child: const Text('Cấp quyền'),
        ),
      ],
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
