import 'package:flutter/material.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/utils/format.dart';
import '../../../core/widgets/app_image.dart';
import '../../../core/widgets/dialogs.dart';
import '../../../core/widgets/price_text.dart';
import '../../../core/widgets/small_badge.dart';
import '../../../core/widgets/state_views.dart';
import '../data/course_admin_repository.dart';
import '../models/admin_models.dart';
import 'admin_course_form_page.dart';
import 'admin_kits_page.dart';
import 'admin_module_page.dart';

/// Màn quản lý một khóa học: thông tin, trạng thái, quà tặng, danh sách module (kéo thả để sắp xếp).
class AdminCourseDetailPage extends StatefulWidget {
  const AdminCourseDetailPage({super.key, required this.courseId});

  final int courseId;

  @override
  State<AdminCourseDetailPage> createState() => _AdminCourseDetailPageState();
}

class _AdminCourseDetailPageState extends State<AdminCourseDetailPage> {
  final _repo = CourseAdminRepository();
  AdminCourseDetail? _detail;
  List<AdminModule> _modules = []; // bản sao để kéo thả mượt, đồng bộ lại từ server sau mỗi lần tải
  String? _error;
  bool _loading = true;
  bool _busy = false;

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
      if (!mounted) return;
      setState(() {
        _detail = detail;
        _modules = List.of(detail.modules);
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

  Future<void> _edit() async {
    final result = await Navigator.of(context).push<int>(
      MaterialPageRoute(builder: (_) => AdminCourseFormPage(initial: _detail)),
    );
    if (result != null) _load(silent: true);
  }

  Future<void> _changeStatus(String target) async {
    setState(() => _busy = true);
    try {
      final updated = await _repo.changeStatus(widget.courseId, target);
      if (!mounted) return;
      setState(() {
        _detail = updated;
        _modules = List.of(updated.modules);
      });
      showMessage(context, target == 'PUBLISHED' ? 'Đã xuất bản khóa học' : 'Đã ngừng bán khóa học');
    } on ApiException catch (e) {
      if (mounted) await showErrorDialog(context, 'Không thể đổi trạng thái', e.displayMessage);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _addModule() async {
    final title = await showTextInputDialog(context, title: 'Thêm module', hint: 'Tên module');
    if (title == null || title.isEmpty) return;
    try {
      await _repo.createModule(widget.courseId, title);
      if (mounted) _load(silent: true);
    } on ApiException catch (e) {
      if (mounted) showMessage(context, e.displayMessage, error: true);
    }
  }

  /// onReorderItem của Flutter đã tự trừ 1 cho newIndex khi kéo xuống, nên dùng thẳng được.
  Future<void> _onReorder(int oldIndex, int newIndex) async {
    if (oldIndex == newIndex) return;
    final before = List.of(_modules);
    setState(() {
      final item = _modules.removeAt(oldIndex);
      _modules.insert(newIndex, item);
    });
    try {
      await _repo.reorderModules(widget.courseId, _modules.map((m) => m.id).toList());
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _modules = before); // lỗi thì trả về thứ tự cũ
      showMessage(context, e.displayMessage, error: true);
    }
  }

  Future<void> _openModule(AdminModule module) async {
    await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => AdminModulePage(courseId: widget.courseId, moduleId: module.id),
    ));
    if (mounted) _load(silent: true);
  }

  Future<void> _editKits() async {
    final saved = await Navigator.of(context).push<bool>(MaterialPageRoute(
      builder: (_) => AdminKitsPage(courseId: widget.courseId, currentKits: _detail!.kits),
    ));
    if (saved == true) _load(silent: true);
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return Scaffold(appBar: AppBar(), body: const LoadingView());
    if (_error != null || _detail == null) {
      return Scaffold(appBar: AppBar(), body: ErrorView(message: _error ?? 'Có lỗi xảy ra', onRetry: _load));
    }
    final detail = _detail!;
    final course = detail.course;

    return Scaffold(
      appBar: AppBar(
        title: Text(course.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(tooltip: 'Sửa thông tin', icon: const Icon(Icons.edit_outlined), onPressed: _edit),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => _load(silent: true),
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _buildInfoCard(detail),
            const SizedBox(height: 12),
            _buildStatusCard(detail),
            const SizedBox(height: 12),
            _buildKitsCard(detail),
            const SizedBox(height: 20),
            Row(
              children: [
                Expanded(
                  child: Text('Module (${_modules.length})',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                ),
                FilledButton.tonalIcon(
                  onPressed: _addModule,
                  icon: const Icon(Icons.add),
                  label: const Text('Thêm module'),
                ),
              ],
            ),
            const SizedBox(height: 4),
            if (_modules.length > 1)
              Text('Giữ biểu tượng ≡ rồi kéo để sắp xếp thứ tự',
                  style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 8),
            if (_modules.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: EmptyView(message: 'Chưa có module. Bấm "Thêm module" để bắt đầu.'),
              )
            else
              ReorderableListView(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                buildDefaultDragHandles: false,
                onReorderItem: _onReorder,
                children: [
                  for (var i = 0; i < _modules.length; i++) _buildModuleTile(i, _modules[i]),
                ],
              ),
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoCard(AdminCourseDetail detail) {
    final course = detail.course;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          AppImage(url: course.thumbnailUrl),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    _statusBadge(course.status),
                    SmallBadge(
                      text: levelLabel(course.level),
                      color: course.level == 'ADVANCED' ? Colors.deepPurple : Theme.of(context).colorScheme.primary,
                    ),
                    Text('Mã: ${course.code}', style: Theme.of(context).textTheme.bodySmall),
                  ],
                ),
                const SizedBox(height: 8),
                PriceText(price: course.price, discountPrice: course.discountPrice),
                const SizedBox(height: 6),
                Text('${course.lessonCount} bài hiển thị · ${formatDuration(course.totalDurationSeconds)}'),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _statusBadge(String status) {
    final color = switch (status) {
      'PUBLISHED' => Colors.green.shade700,
      'HIDDEN' => Colors.grey.shade700,
      _ => Colors.amber.shade800,
    };
    return SmallBadge(text: statusLabel(status), color: color);
  }

  Widget _buildStatusCard(AdminCourseDetail detail) {
    final status = detail.course.status;
    final problems = detail.publishProblems;
    final canPublish = problems.isEmpty;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Trạng thái bán', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 8),
            if (problems.isNotEmpty)
              Container(
                width: double.infinity,
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: Colors.amber.shade50,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      status == 'PUBLISHED' ? 'Cần xử lý:' : 'Cần bổ sung trước khi xuất bản:',
                      style: TextStyle(fontWeight: FontWeight.w600, color: Colors.amber.shade900),
                    ),
                    const SizedBox(height: 4),
                    for (final p in problems) Text('• $p'),
                  ],
                ),
              ),
            if (status == 'PUBLISHED')
              OutlinedButton.icon(
                onPressed: _busy ? null : () => _changeStatus('HIDDEN'),
                icon: const Icon(Icons.visibility_off_outlined),
                label: const Text('Ngừng bán (ẩn khỏi học viên)'),
              )
            else
              FilledButton.icon(
                onPressed: (_busy || !canPublish) ? null : () => _changeStatus('PUBLISHED'),
                icon: const Icon(Icons.publish),
                label: Text(status == 'DRAFT' ? 'Xuất bản khóa học' : 'Bán lại khóa học'),
              ),
            if (status == 'HIDDEN')
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(
                  'Khóa đang ngừng bán: học viên không thấy, nhưng người đã mua vẫn học được.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildKitsCard(AdminCourseDetail detail) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.card_giftcard, size: 20, color: Color(0xFFE65100)),
                const SizedBox(width: 8),
                Expanded(child: Text('Dụng cụ tặng kèm', style: Theme.of(context).textTheme.titleSmall)),
                TextButton(onPressed: _editKits, child: const Text('Chỉnh sửa')),
              ],
            ),
            if (detail.kits.isEmpty)
              Text('Chưa có quà tặng kèm', style: Theme.of(context).textTheme.bodySmall)
            else
              for (final kit in detail.kits) Text('• ${kit.name} x${kit.quantity}'),
          ],
        ),
      ),
    );
  }

  Widget _buildModuleTile(int index, AdminModule module) {
    return Card(
      key: ValueKey('module-${module.id}'),
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: ReorderableDragStartListener(
          index: index,
          child: const Icon(Icons.drag_handle),
        ),
        title: Text(
          module.title,
          style: module.hidden ? const TextStyle(color: Colors.grey) : null,
        ),
        subtitle: Text('${module.lessons.length} bài học${module.hidden ? ' · Đang ẩn' : ''}'),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => _openModule(module),
      ),
    );
  }
}
