import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/network/media_url.dart';
import '../../../core/widgets/dialogs.dart';
import '../../../core/widgets/state_views.dart';
import '../data/course_admin_repository.dart';
import '../models/admin_models.dart';
import 'upload_field.dart';

/// Form thêm / sửa bài học. Truyền [lesson] để sửa; bỏ trống để thêm mới vào [moduleId].
/// Lưu xong thì pop trả về true.
class AdminLessonFormPage extends StatefulWidget {
  const AdminLessonFormPage({super.key, required this.moduleId, this.lesson});

  final int moduleId;
  final AdminLesson? lesson;

  @override
  State<AdminLessonFormPage> createState() => _AdminLessonFormPageState();
}

class _AdminLessonFormPageState extends State<AdminLessonFormPage> {
  final _repo = CourseAdminRepository();
  final _formKey = GlobalKey<FormState>();

  late final TextEditingController _title;
  late final TextEditingController _desc;
  late final TextEditingController _duration;
  String? _videoUrl;
  String? _materialUrl;
  bool _preview = false;
  bool _required = false;
  bool _hidden = false;
  bool _saving = false;
  bool _readingDuration = false;

  bool get _isEdit => widget.lesson != null;

  @override
  void initState() {
    super.initState();
    final l = widget.lesson;
    _title = TextEditingController(text: l?.title ?? '');
    _desc = TextEditingController(text: l?.description ?? '');
    _duration = TextEditingController(text: '${l?.durationSeconds ?? 0}');
    _videoUrl = l?.videoUrl;
    _materialUrl = l?.materialUrl;
    _preview = l?.preview ?? false;
    _required = l?.required ?? false;
    _hidden = l?.hidden ?? false;
  }

  @override
  void dispose() {
    _title.dispose();
    _desc.dispose();
    _duration.dispose();
    super.dispose();
  }

  /// Mở video để đọc thời lượng thật rồi điền vào ô "Thời lượng".
  Future<void> _readDurationFromVideo() async {
    final url = _videoUrl;
    if (url == null || url.isEmpty) return;
    setState(() => _readingDuration = true);
    final controller = VideoPlayerController.networkUrl(Uri.parse(resolveMediaUrl(url)));
    try {
      await controller.initialize();
      if (!mounted) return;
      setState(() => _duration.text = '${controller.value.duration.inSeconds}');
    } catch (_) {
      if (mounted) showMessage(context, 'Không đọc được thời lượng, hãy nhập tay', error: true);
    } finally {
      await controller.dispose();
      if (mounted) setState(() => _readingDuration = false);
    }
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    try {
      await _repo.saveLesson(
        lessonId: widget.lesson?.id,
        moduleId: widget.moduleId,
        title: _title.text.trim(),
        description: _desc.text.trim(),
        videoUrl: _videoUrl,
        durationSeconds: int.tryParse(_duration.text.trim()) ?? 0,
        materialUrl: _materialUrl,
        preview: _preview,
        required: _required,
        hidden: _hidden,
      );
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      // Ví dụ: khóa đang bán thì không được bỏ video / ẩn bài cuối cùng
      await showErrorDialog(context, 'Không thể lưu bài học', e.displayMessage);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isEdit ? 'Sửa bài học' : 'Thêm bài học')),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            TextFormField(
              controller: _title,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(labelText: 'Tên bài học *'),
              validator: (v) => (v == null || v.trim().isEmpty) ? 'Tên bài học không được để trống' : null,
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _desc,
              maxLines: 3,
              decoration: const InputDecoration(labelText: 'Mô tả', alignLabelWithHint: true),
            ),
            const SizedBox(height: 12),
            UploadField(
              label: 'Video bài học (mp4)',
              kind: UploadKind.video,
              value: _videoUrl,
              onChanged: (url) => setState(() => _videoUrl = url),
            ),
            const SizedBox(height: 12),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: TextFormField(
                    controller: _duration,
                    keyboardType: TextInputType.number,
                    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                    decoration: const InputDecoration(labelText: 'Thời lượng (giây)'),
                  ),
                ),
                const SizedBox(width: 8),
                if (_videoUrl != null && _videoUrl!.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: OutlinedButton(
                      onPressed: _readingDuration ? null : _readDurationFromVideo,
                      child: _readingDuration
                          ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Text('Đọc từ video'),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            UploadField(
              label: 'Tài liệu đính kèm (PDF)',
              kind: UploadKind.document,
              value: _materialUrl,
              onChanged: (url) => setState(() => _materialUrl = url),
            ),
            const SizedBox(height: 8),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Học thử miễn phí'),
              subtitle: const Text('Ai cũng xem được video bài này, kể cả chưa mua'),
              value: _preview,
              onChanged: (v) => setState(() => _preview = v),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Bài bắt buộc'),
              subtitle: const Text('Dùng để xét hoàn thành khóa học'),
              value: _required,
              onChanged: (v) => setState(() => _required = v),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Ẩn bài học'),
              subtitle: const Text('Học viên không thấy bài này (không xóa dữ liệu)'),
              value: _hidden,
              onChanged: (v) => setState(() => _hidden = v),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(_isEdit ? 'Lưu thay đổi' : 'Thêm bài học'),
            ),
          ],
        ),
      ),
    );
  }
}
