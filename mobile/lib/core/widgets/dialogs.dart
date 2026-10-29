import 'package:flutter/material.dart';

/// Hộp thoại nhập một dòng chữ. Trả về null nếu bấm Hủy.
///
/// Lưu ý cho người mới: TextEditingController của hộp thoại phải do CHÍNH hộp thoại (một StatefulWidget)
/// tạo và dispose. Nếu tạo ở ngoài rồi dispose ngay sau `await showDialog`, hộp thoại vẫn đang chạy
/// hiệu ứng đóng và còn dùng controller => app báo lỗi.
Future<String?> showTextInputDialog(
  BuildContext context, {
  required String title,
  String initial = '',
  String? hint,
  String confirmLabel = 'Lưu',
  TextInputType? keyboardType,
  TextCapitalization capitalization = TextCapitalization.sentences,
}) {
  return showDialog<String>(
    context: context,
    builder: (context) => _TextInputDialog(
      title: title,
      initial: initial,
      hint: hint,
      confirmLabel: confirmLabel,
      keyboardType: keyboardType,
      capitalization: capitalization,
    ),
  );
}

class _TextInputDialog extends StatefulWidget {
  const _TextInputDialog({
    required this.title,
    required this.initial,
    required this.hint,
    required this.confirmLabel,
    required this.keyboardType,
    required this.capitalization,
  });

  final String title;
  final String initial;
  final String? hint;
  final String confirmLabel;
  final TextInputType? keyboardType;
  final TextCapitalization capitalization;

  @override
  State<_TextInputDialog> createState() => _TextInputDialogState();
}

class _TextInputDialogState extends State<_TextInputDialog> {
  late final TextEditingController _controller = TextEditingController(text: widget.initial);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: TextField(
        controller: _controller,
        autofocus: true,
        keyboardType: widget.keyboardType,
        textCapitalization: widget.capitalization,
        decoration: InputDecoration(hintText: widget.hint),
        onSubmitted: (v) => Navigator.pop(context, v.trim()),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Hủy')),
        FilledButton(
          onPressed: () => Navigator.pop(context, _controller.text.trim()),
          child: Text(widget.confirmLabel),
        ),
      ],
    );
  }
}

/// Hộp thoại báo lỗi (dùng khi thông báo dài, ví dụ danh sách điều kiện xuất bản còn thiếu).
Future<void> showErrorDialog(BuildContext context, String title, String message) {
  return showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      icon: Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
      title: Text(title),
      content: SingleChildScrollView(child: Text(message)),
      actions: [
        FilledButton(onPressed: () => Navigator.pop(context), child: const Text('Đã hiểu')),
      ],
    ),
  );
}

/// Hộp thoại xác nhận Có/Không.
Future<bool> confirmDialog(
  BuildContext context, {
  required String title,
  required String message,
  String confirmLabel = 'Đồng ý',
}) async {
  final result = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Hủy')),
        FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(confirmLabel)),
      ],
    ),
  );
  return result ?? false;
}
