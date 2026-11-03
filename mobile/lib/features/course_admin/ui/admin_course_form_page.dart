import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/widgets/state_views.dart';
import '../data/course_admin_repository.dart';
import '../models/admin_models.dart';
import 'upload_field.dart';

/// Form thêm / sửa thông tin chung của khóa học.
/// Truyền [initial] để sửa; bỏ trống để thêm mới. Khi lưu xong, trang này pop trả về id của khóa.
class AdminCourseFormPage extends StatefulWidget {
  const AdminCourseFormPage({super.key, this.initial});

  final AdminCourseDetail? initial;

  @override
  State<AdminCourseFormPage> createState() => _AdminCourseFormPageState();
}

class _AdminCourseFormPageState extends State<AdminCourseFormPage> {
  final _repo = CourseAdminRepository();
  final _formKey = GlobalKey<FormState>();

  late final TextEditingController _code;
  late final TextEditingController _title;
  late final TextEditingController _shortDesc;
  late final TextEditingController _desc;
  late final TextEditingController _price;
  late final TextEditingController _discount;
  String? _thumbnailUrl;
  String _level = 'BASIC';
  bool _saving = false;
  Map<String, String> _serverErrors = {};

  bool get _isEdit => widget.initial != null;

  @override
  void initState() {
    super.initState();
    final c = widget.initial?.course;
    _code = TextEditingController(text: c?.code ?? '');
    _title = TextEditingController(text: c?.title ?? '');
    _shortDesc = TextEditingController(text: c?.shortDescription ?? '');
    _desc = TextEditingController(text: widget.initial?.description ?? '');
    _price = TextEditingController(text: c == null ? '' : '${c.price}');
    _discount = TextEditingController(text: c?.discountPrice == null ? '' : '${c!.discountPrice}');
    _thumbnailUrl = c?.thumbnailUrl;
    _level = c?.level ?? 'BASIC';
  }

  @override
  void dispose() {
    for (final c in [_code, _title, _shortDesc, _desc, _price, _discount]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _serverErrors = {});
    if (!_formKey.currentState!.validate()) return;

    setState(() => _saving = true);
    try {
      final saved = await _repo.saveCourse(
        id: widget.initial?.course.id,
        code: _code.text.trim().isEmpty ? null : _code.text.trim(),
        title: _title.text.trim(),
        shortDescription: _shortDesc.text.trim(),
        description: _desc.text.trim(),
        thumbnailUrl: _thumbnailUrl,
        price: int.parse(_price.text.trim()),
        discountPrice: _discount.text.trim().isEmpty ? null : int.parse(_discount.text.trim()),
        level: _level,
      );
      if (!mounted) return;
      Navigator.of(context).pop(saved.course.id);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _serverErrors = e.fieldErrorMap;
      });
      // Gom lỗi từ backend (ví dụ "Giá giảm phải nhỏ hơn giá gốc") hiển thị ngay
      _formKey.currentState!.validate();
      showMessage(context, e.displayMessage, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isEdit ? 'Sửa khóa học' : 'Thêm khóa học')),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            TextFormField(
              controller: _title,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(labelText: 'Tên khóa học *'),
              validator: (v) => (v == null || v.trim().isEmpty)
                  ? 'Tên khóa học không được để trống'
                  : _serverErrors['title'],
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _code,
              decoration: const InputDecoration(
                labelText: 'Mã khóa học (không bắt buộc)',
                helperText: 'Để trống để hệ thống tự sinh. Chỉ gồm chữ, số, - và _',
              ),
              validator: (v) => _serverErrors['code'],
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _shortDesc,
              maxLines: 2,
              maxLength: 500,
              decoration: const InputDecoration(
                labelText: 'Mô tả ngắn',
                helperText: 'Bắt buộc khi xuất bản',
              ),
              validator: (v) => _serverErrors['shortDescription'],
            ),
            const SizedBox(height: 4),
            TextFormField(
              controller: _desc,
              maxLines: 6,
              decoration: const InputDecoration(labelText: 'Mô tả chi tiết', alignLabelWithHint: true),
              validator: (v) => _serverErrors['description'],
            ),
            const SizedBox(height: 12),
            UploadField(
              label: 'Ảnh bìa',
              kind: UploadKind.image,
              value: _thumbnailUrl,
              onChanged: (url) => setState(() => _thumbnailUrl = url),
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: _price,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: const InputDecoration(labelText: 'Giá gốc (VND) *', suffixText: '₫'),
              validator: (v) {
                if (v == null || v.trim().isEmpty) return 'Nhập giá gốc';
                if (int.tryParse(v.trim()) == null) return 'Giá không hợp lệ';
                return _serverErrors['price'];
              },
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _discount,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: const InputDecoration(
                labelText: 'Giá giảm (VND, không bắt buộc)',
                helperText: 'Phải nhỏ hơn giá gốc. Để trống nếu không giảm giá.',
                suffixText: '₫',
              ),
              validator: (v) {
                if (v == null || v.trim().isEmpty) return _serverErrors['discountPrice'];
                final discount = int.tryParse(v.trim());
                final price = int.tryParse(_price.text.trim());
                if (discount == null || discount <= 0) return 'Giá giảm phải lớn hơn 0';
                if (price != null && discount >= price) return 'Giá giảm phải nhỏ hơn giá gốc';
                return _serverErrors['discountPrice'];
              },
            ),
            const SizedBox(height: 16),
            Text('Cấp độ', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 8),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'BASIC', label: Text('Cơ bản')),
                ButtonSegment(value: 'ADVANCED', label: Text('Nâng cao')),
              ],
              selected: {_level},
              onSelectionChanged: (s) => setState(() => _level = s.first),
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(_isEdit ? 'Lưu thay đổi' : 'Tạo khóa học (nháp)'),
            ),
          ],
        ),
      ),
    );
  }
}
