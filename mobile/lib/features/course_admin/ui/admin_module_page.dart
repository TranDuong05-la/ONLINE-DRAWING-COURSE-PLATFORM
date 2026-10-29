import 'package:flutter/material.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/utils/format.dart';
import '../../../core/widgets/dialogs.dart';
import '../../../core/widgets/small_badge.dart';
import '../../../core/widgets/state_views.dart';
import '../data/course_admin_repository.dart';
import '../models/admin_models.dart';
import 'admin_lesson_form_page.dart';

/// Quản lý bài học trong một module: thêm, sửa, ẩn, kéo thả sắp xếp.
class AdminModulePage extends StatefulWidget {
  const AdminModulePage({super.key, required this.courseId, required this.moduleId});

  final int courseId;
  final int moduleId;

  @override
  State<AdminModulePage> createState() => _AdminModulePageState();
}

/// Hộp thoại sửa module (tên + ẩn/hiện). Tự quản lý controller của chính nó (xem ghi chú ở dialogs.dart).
class _EditModuleDialog extends StatefulWidget {
  const _EditModuleDialog({required this.title, required this.hidden});

  final String title;
  final bool hidden;

  @override
  State<_EditModuleDialog> createState() => _EditModuleDialogState();
}

class _EditModuleDialogState extends State<_EditModuleDialog> {
  late final TextEditingController _title = TextEditingController(text: widget.title);
  late bool _hidden = widget.hidden;

  @override
  void dispose() {
    _title.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Sửa module'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _title,
            autofocus: true,
            decoration: const InputDecoration(labelText: 'Tên module'),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Ẩn module'),
            subtitle: const Text('Học viên không thấy module và các bài bên trong'),
            value: _hidden,
            onChanged: (v) => setState(() => _hidden = v),
          ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Hủy')),
        FilledButton(
          onPressed: () => Navigator.pop(context, (title: _title.text.trim(), hidden: _hidden)),
          child: const Text('Lưu'),
        ),
      ],
    );
  }
}

class _AdminModulePageState extends State<AdminModulePage> {
  final _repo = CourseAdminRepository();
  AdminModule? _module;
  List<AdminLesson> _lessons = [];
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool silent = false}) async {
    if (!silent) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final detail = await _repo.getCourse(widget.courseId);
      final module = detail.modules.where((m) => m.id == widget.moduleId).firstOrNull;
      if (!mounted) return;
      if (module == null) {
        setState(() {
          _error = 'Không tìm thấy module';
          _loading = false;
        });
        return;
      }
      setState(() {
        _module = module;
        _lessons = List.of(module.lessons);
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.displayMessage;
        _loading = false;
      });
    }
  }

  /// Đổi tên module và/hoặc ẩn/hiện.
  Future<void> _editModule() async {
    final module = _module!;
    final result = await showDialog<({String title, bool hidden})>(
      context: context,
      builder: (context) => _EditModuleDialog(title: module.title, hidden: module.hidden),
    );
    if (result == null || result.title.isEmpty) return;
    try {
      await _repo.updateModule(module.id, title: result.title, hidden: result.hidden);
      if (mounted) _load(silent: true);
    } on ApiException catch (e) {
      // Ví dụ: khóa đang bán không được ẩn module chứa bài cuối cùng
      if (mounted) await showErrorDialog(context, 'Không thể lưu module', e.displayMessage);
    }
  }

  Future<void> _openLesson([AdminLesson? lesson]) async {
    final saved = await Navigator.of(context).push<bool>(MaterialPageRoute(
      builder: (_) => AdminLessonFormPage(moduleId: widget.moduleId, lesson: lesson),
    ));
    if (saved == true && mounted) _load(silent: true);
  }

  /// onReorderItem của Flutter đã tự trừ 1 cho newIndex khi kéo xuống, nên dùng thẳng được.
  Future<void> _onReorder(int oldIndex, int newIndex) async {
    if (oldIndex == newIndex) return;
    final before = List.of(_lessons);
    setState(() {
      final item = _lessons.removeAt(oldIndex);
      _lessons.insert(newIndex, item);
    });
    try {
      await _repo.reorderLessons(widget.moduleId, _lessons.map((l) => l.id).toList());
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _lessons = before);
      showMessage(context, e.displayMessage, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return Scaffold(appBar: AppBar(), body: const LoadingView());
    if (_error != null || _module == null) {
      return Scaffold(appBar: AppBar(), body: ErrorView(message: _error ?? 'Có lỗi xảy ra', onRetry: _load));
    }
    final module = _module!;

    return Scaffold(
      appBar: AppBar(
        title: Text(module.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(tooltip: 'Sửa module', icon: const Icon(Icons.edit_outlined), onPressed: _editModule),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        heroTag: null, // xem ghi chú ở admin_bundle_list_page.dart
        onPressed: () => _openLesson(),
        icon: const Icon(Icons.add),
        label: const Text('Thêm bài học'),
      ),
      body: RefreshIndicator(
        onRefresh: () => _load(silent: true),
        child: _lessons.isEmpty
            ? LayoutBuilder(
                builder: (context, c) => ListView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  children: [
                    SizedBox(
                      height: c.maxHeight,
                      child: const EmptyView(message: 'Module chưa có bài học. Bấm "Thêm bài học".'),
                    ),
                  ],
                ),
              )
            : ReorderableListView(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 96),
                buildDefaultDragHandles: false,
                onReorderItem: _onReorder,
                header: module.hidden
                    ? Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: SmallBadge(text: 'Module đang ẩn với học viên', color: Colors.grey.shade700),
                      )
                    : null,
                children: [
                  for (var i = 0; i < _lessons.length; i++) _buildLessonTile(i, _lessons[i]),
                ],
              ),
      ),
    );
  }

  Widget _buildLessonTile(int index, AdminLesson lesson) {
    final missingVideo = lesson.videoUrl == null || lesson.videoUrl!.isEmpty;
    return Card(
      key: ValueKey('lesson-${lesson.id}'),
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: ReorderableDragStartListener(index: index, child: const Icon(Icons.drag_handle)),
        title: Text(
          lesson.title,
          style: lesson.hidden ? const TextStyle(color: Colors.grey) : null,
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Wrap(
            spacing: 6,
            runSpacing: 4,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(formatDuration(lesson.durationSeconds), style: Theme.of(context).textTheme.bodySmall),
              if (lesson.preview) SmallBadge(text: 'Học thử', color: Colors.green.shade700),
              if (lesson.required) SmallBadge(text: 'Bắt buộc', color: Colors.orange.shade800),
              if (lesson.hidden) SmallBadge(text: 'Đang ẩn', color: Colors.grey.shade700),
              if (missingVideo) SmallBadge(text: 'Thiếu video', color: Theme.of(context).colorScheme.error),
              if (lesson.materialUrl != null && lesson.materialUrl!.isNotEmpty)
                const Icon(Icons.attach_file, size: 16),
            ],
          ),
        ),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => _openLesson(lesson),
      ),
    );
  }
}
