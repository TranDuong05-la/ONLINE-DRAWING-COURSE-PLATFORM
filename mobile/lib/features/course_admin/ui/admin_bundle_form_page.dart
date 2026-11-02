import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/utils/format.dart';
import '../../../core/widgets/dialogs.dart';
import '../../../core/widgets/state_views.dart';
import '../../bundle/models/bundle_models.dart';
import '../../course/models/course_models.dart';
import '../data/course_admin_repository.dart';

/// Form thêm / sửa combo. Truyền [bundle] để sửa. Lưu xong pop trả về true.
class AdminBundleFormPage extends StatefulWidget {
  const AdminBundleFormPage({super.key, this.bundle});

  final AdminBundle? bundle;

  @override
  State<AdminBundleFormPage> createState() => _AdminBundleFormPageState();
}

class _AdminBundleFormPageState extends State<AdminBundleFormPage> {
  final _repo = CourseAdminRepository();
  final _formKey = GlobalKey<FormState>();

  late final TextEditingController _title;
  late final TextEditingController _code;
  late final TextEditingController _desc;
  late final TextEditingController _price;

  List<CourseSummary> _publishedCourses = [];
  final List<int> _selectedIds = []; // giữ thứ tự admin tick
  String? _loadError;
  bool _loading = true;
  bool _saving = false;

  bool get _isEdit => widget.bundle != null;

  @override
  void initState() {
    super.initState();
    final b = widget.bundle;
    _title = TextEditingController(text: b?.title ?? '');
    _code = TextEditingController(text: b?.code ?? '');
    _desc = TextEditingController(text: b?.description ?? '');
    _price = TextEditingController(text: b == null ? '' : '${b.price}');
    if (b != null) _selectedIds.addAll(b.courses.map((c) => c.id));
    _loadCourses();
  }

  @override
  void dispose() {
    for (final c in [_title, _code, _desc, _price]) {
      c.dispose();
    }
    super.dispose();
  }

  /// Chỉ khóa đang bán (PUBLISHED) mới được đưa vào combo.
  Future<void> _loadCourses() async {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    try {
      final result = await _repo.listCourses(status: 'PUBLISHED', size: 100);
      if (!mounted) return;
      setState(() {
        _publishedCourses = result.items;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loadError = e.displayMessage;
        _loading = false;
      });
    }
  }

  /// Tổng giá thực bán nếu mua lẻ các khóa đã chọn (giá combo phải nhỏ hơn số này).
  int get _selectedTotal {
    var total = 0;
    for (final id in _selectedIds) {
      final course = _publishedCourses.where((c) => c.id == id).firstOrNull ??
          widget.bundle?.courses.where((c) => c.id == id).firstOrNull;
      total += course?.effectivePrice ?? 0;
    }
    return total;
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    if (_selectedIds.length < 2) {
      showMessage(context, 'Combo cần ít nhất 2 khóa học', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      await _repo.saveBundle(
        id: widget.bundle?.id,
        code: _code.text.trim().isEmpty ? null : _code.text.trim(),
        title: _title.text.trim(),
        description: _desc.text.trim(),
        price: int.parse(_price.text.trim()),
        courseIds: _selectedIds,
      );
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      await showErrorDialog(context, 'Không thể lưu combo', e.displayMessage);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(_isEdit ? 'Sửa combo' : 'Thêm combo')),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            TextFormField(
              controller: _title,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(labelText: 'Tên combo *'),
              validator: (v) => (v == null || v.trim().isEmpty) ? 'Tên combo không được để trống' : null,
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _code,
              decoration: const InputDecoration(
                labelText: 'Mã combo (không bắt buộc)',
                helperText: 'Để trống để hệ thống tự sinh',
              ),
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _desc,
              maxLines: 3,
              decoration: const InputDecoration(labelText: 'Mô tả', alignLabelWithHint: true),
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _price,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(labelText: 'Giá combo (VND) *', suffixText: '₫'),
              validator: (v) {
                final price = int.tryParse((v ?? '').trim());
                if (price == null || price <= 0) return 'Nhập giá combo lớn hơn 0';
                final total = _selectedTotal;
                if (_selectedIds.length >= 2 && price >= total) {
                  return 'Giá combo phải nhỏ hơn tổng giá các khóa (${formatPrice(total)})';
                }
                return null;
              },
            ),
            const SizedBox(height: 16),
            Text('Chọn khóa học (tối thiểu 2, chỉ khóa đang bán)',
                style: theme.textTheme.titleSmall),
            const SizedBox(height: 4),
            Text(
              'Đã chọn ${_selectedIds.length} khóa · Tổng giá mua lẻ: ${formatPrice(_selectedTotal)}',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            if (_loading)
              const Padding(padding: EdgeInsets.all(24), child: LoadingView())
            else if (_loadError != null)
              SizedBox(height: 160, child: ErrorView(message: _loadError!, onRetry: _loadCourses))
            else if (_publishedCourses.isEmpty)
              const Padding(
                padding: EdgeInsets.all(16),
                child: Text('Chưa có khóa học nào đang bán để đưa vào combo.'),
              )
            else
              for (final course in _publishedCourses)
                Card(
                  margin: const EdgeInsets.only(bottom: 8),
                  child: CheckboxListTile(
                    value: _selectedIds.contains(course.id),
                    onChanged: (v) => setState(() {
                      if (v == true) {
                        _selectedIds.add(course.id);
                      } else {
                        _selectedIds.remove(course.id);
                      }
                    }),
                    title: Text(course.title),
                    subtitle: Text(formatPrice(course.effectivePrice)),
                  ),
                ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(_isEdit ? 'Lưu thay đổi' : 'Tạo combo'),
            ),
          ],
        ),
      ),
    );
  }
}
