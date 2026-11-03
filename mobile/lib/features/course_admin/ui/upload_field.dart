import 'package:file_selector/file_selector.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/network/media_url.dart';
import '../../../core/widgets/app_image.dart';
import '../../../core/widgets/dialogs.dart';
import '../../../core/widgets/state_views.dart';
import '../../../core/widgets/video_player_page.dart';
import '../data/course_admin_repository.dart';

enum UploadKind { image, video, document }

/// Ô chọn file: bấm "Chọn file" để upload lên server, hoặc "Dán link" để dùng link ngoài.
/// Giá trị trả về qua [onChanged] là đường dẫn tương đối ("/uploads/abc.mp4") hoặc link http(s).
class UploadField extends StatefulWidget {
  const UploadField({
    super.key,
    required this.label,
    required this.kind,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final UploadKind kind;
  final String? value;
  final ValueChanged<String?> onChanged;

  @override
  State<UploadField> createState() => _UploadFieldState();
}

class _UploadFieldState extends State<UploadField> {
  final _repo = CourseAdminRepository();
  bool _uploading = false;
  double _progress = 0;

  List<String> get _extensions => switch (widget.kind) {
        UploadKind.image => ['jpg', 'jpeg', 'png', 'webp'],
        UploadKind.video => ['mp4'],
        UploadKind.document => ['pdf'],
      };

  /// Loại nội dung (MIME) tương ứng, để hộp chọn file của Android lọc đúng loại.
  List<String> get _mimeTypes => switch (widget.kind) {
        UploadKind.image => ['image/jpeg', 'image/png', 'image/webp'],
        UploadKind.video => ['video/mp4'],
        UploadKind.document => ['application/pdf'],
      };

  Future<void> _pickAndUpload() async {
    // Dùng file_selector (package chính thức của Flutter) thay cho file_picker: file_picker không
    // biên dịch được với AGP 9 của app (bản <= 11) hoặc xung đột win32 với flutter_secure_storage (bản >= 13).
    final file = await openFile(
      acceptedTypeGroups: [
        XTypeGroup(label: widget.label, extensions: _extensions, mimeTypes: _mimeTypes),
      ],
    );
    if (file == null) return; // người dùng bấm hủy
    final path = file.path;
    if (path.isEmpty) {
      if (mounted) showMessage(context, 'Không đọc được file đã chọn', error: true);
      return;
    }

    setState(() {
      _uploading = true;
      _progress = 0;
    });
    try {
      final url = await _repo.upload(
        filePath: path,
        fileName: file.name,
        onProgress: (p) {
          if (mounted) setState(() => _progress = p);
        },
      );
      if (!mounted) return;
      widget.onChanged(url);
    } on ApiException catch (e) {
      if (mounted) showMessage(context, e.displayMessage, error: true);
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _pasteLink() async {
    final link = await showTextInputDialog(
      context,
      title: 'Dán link',
      initial: widget.value ?? '',
      hint: 'https://...',
      confirmLabel: 'Dùng link này',
      keyboardType: TextInputType.url,
      capitalization: TextCapitalization.none,
    );
    if (link == null) return;
    if (link.isEmpty) {
      widget.onChanged(null);
    } else if (link.startsWith('http://') || link.startsWith('https://')) {
      widget.onChanged(link);
    } else if (mounted) {
      showMessage(context, 'Link phải bắt đầu bằng http:// hoặc https://', error: true);
    }
  }

  Future<void> _openPreview() async {
    final value = widget.value;
    if (value == null || value.isEmpty) return;
    if (widget.kind == UploadKind.video) {
      await Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => VideoPlayerPage(title: widget.label, url: value)),
      );
    } else if (widget.kind == UploadKind.document) {
      final ok = await launchUrl(
        Uri.parse(resolveMediaUrl(value)),
        mode: LaunchMode.externalApplication,
      );
      if (!ok && mounted) showMessage(context, 'Không mở được tài liệu', error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final value = widget.value;
    final hasValue = value != null && value.isNotEmpty;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        border: Border.all(color: theme.colorScheme.outlineVariant),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(widget.label, style: theme.textTheme.titleSmall),
          const SizedBox(height: 8),
          if (hasValue && widget.kind == UploadKind.image)
            SizedBox(
              width: 200,
              child: AppImage(url: value, aspectRatio: 16 / 9, borderRadius: 8),
            ),
          if (hasValue)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  Icon(
                    switch (widget.kind) {
                      UploadKind.image => Icons.image_outlined,
                      UploadKind.video => Icons.movie_outlined,
                      UploadKind.document => Icons.picture_as_pdf_outlined,
                    },
                    size: 18,
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall),
                  ),
                ],
              ),
            ),
          if (_uploading) ...[
            LinearProgressIndicator(value: _progress == 0 ? null : _progress),
            const SizedBox(height: 4),
            Text('Đang tải lên ${(_progress * 100).round()}%', style: theme.textTheme.bodySmall),
          ] else
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                OutlinedButton.icon(
                  onPressed: _pickAndUpload,
                  icon: const Icon(Icons.upload_file),
                  label: const Text('Chọn file'),
                ),
                TextButton.icon(
                  onPressed: _pasteLink,
                  icon: const Icon(Icons.link),
                  label: const Text('Dán link'),
                ),
                if (hasValue && widget.kind != UploadKind.image)
                  TextButton.icon(
                    onPressed: _openPreview,
                    icon: Icon(widget.kind == UploadKind.video ? Icons.play_arrow : Icons.open_in_new),
                    label: Text(widget.kind == UploadKind.video ? 'Xem thử' : 'Mở'),
                  ),
                if (hasValue)
                  TextButton.icon(
                    onPressed: () => widget.onChanged(null),
                    icon: const Icon(Icons.delete_outline),
                    label: const Text('Bỏ'),
                  ),
              ],
            ),
        ],
      ),
    );
  }
}
