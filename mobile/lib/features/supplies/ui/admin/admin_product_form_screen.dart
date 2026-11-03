import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../../core/network/api_exceptions.dart';
import '../widgets/supply_states.dart';
import '../../models/supply_models.dart';
import '../../data/supply_repository.dart';
import '../widgets/supply_widgets.dart';

/// Tạo / sửa học cụ. Trả về true khi đã lưu.
class AdminProductFormScreen extends StatefulWidget {
  final SupplyProduct? product;
  const AdminProductFormScreen({super.key, this.product});

  @override
  State<AdminProductFormScreen> createState() => _AdminProductFormScreenState();
}

class _AdminProductFormScreenState extends State<AdminProductFormScreen> {
  final _form = GlobalKey<FormState>();
  late final _sku = TextEditingController(text: widget.product?.sku);
  late final _name = TextEditingController(text: widget.product?.name);
  late final _desc = TextEditingController(text: widget.product?.description);
  late final _price = TextEditingController(text: widget.product?.price.toString());
  late final _image = TextEditingController(text: widget.product?.thumbnailUrl);
  late final _threshold = TextEditingController(text: '${widget.product?.lowStockThreshold ?? 5}');
  final _initialStock = TextEditingController(text: '0');

  List<SupplyCategory>? _categories;
  late int? _categoryId = widget.product?.categoryId;
  late bool _active = !(widget.product?.hidden ?? false);
  bool _saving = false;
  Map<String, dynamic> _serverErrors = {};

  bool get _isEdit => widget.product != null;

  @override
  void initState() {
    super.initState();
    _image.addListener(() => setState(() {}));
    SupplyRepository.instance
        .adminCategories()
        .then((c) {
          if (mounted) setState(() => _categories = c);
        })
        .catchError((e) {
          if (mounted) showMessage(context, 'Không tải được danh mục: $e');
        });
  }

  @override
  void dispose() {
    for (final c in [_sku, _name, _desc, _price, _image, _threshold, _initialStock]) {
      c.dispose();
    }
    super.dispose();
  }

  String? _serverError(String field) => _serverErrors[field]?.toString();

  Future<void> _save() async {
    setState(() => _serverErrors = {});
    if (!_form.currentState!.validate()) return;
    setState(() => _saving = true);
    try {
      final body = {
        'categoryId': _categoryId,
        'sku': _sku.text.trim(),
        'name': _name.text.trim(),
        'description': _desc.text.trim(),
        'price': int.parse(_price.text),
        'thumbnailUrl': _image.text.trim(),
        'lowStockThreshold': int.tryParse(_threshold.text) ?? 5,
        'active': _active,
        if (!_isEdit) 'initialStock': int.tryParse(_initialStock.text) ?? 0,
      };
      await SupplyRepository.instance.saveProduct(widget.product?.id, body);
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _serverErrors = {for (final err in e.errors ?? const []) err.field: err.message};
      });
      _form.currentState!.validate();
      showMessage(context, e.message);
    } catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      showMessage(context, e.toString());
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isEdit ? 'Sửa học cụ' : 'Thêm học cụ')),
      body: _categories == null
          ? const LoadingView()
          : Form(
              key: _form,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (_categories!.isEmpty)
                    const Card(
                      child: ListTile(
                        leading: Icon(Icons.info_outline),
                        title: Text('Chưa có danh mục. Hãy tạo danh mục ở tab "Danh mục" trước.'),
                      ),
                    ),
                  DropdownButtonFormField<int>(
                    initialValue: _categoryId,
                    decoration: const InputDecoration(labelText: 'Danh mục *'),
                    items: [
                      for (final c in _categories!)
                        DropdownMenuItem(value: c.id, child: Text(c.active ? c.name : '${c.name} (đã ẩn)')),
                    ],
                    onChanged: (v) => setState(() => _categoryId = v),
                    validator: (v) => v == null ? 'Chọn danh mục' : _serverError('categoryId'),
                  ),
                  TextFormField(
                    controller: _sku,
                    textCapitalization: TextCapitalization.characters,
                    decoration: const InputDecoration(
                      labelText: 'SKU *',
                      helperText: 'Mã riêng của sản phẩm, vd. PEN-2B-12',
                    ),
                    validator: (v) {
                      final s = v?.trim() ?? '';
                      if (s.isEmpty) return 'Nhập SKU';
                      if (!RegExp(r'^[A-Za-z0-9][A-Za-z0-9_-]{1,49}$').hasMatch(s)) {
                        return 'SKU 2-50 ký tự, chỉ gồm chữ, số, - và _';
                      }
                      return _serverError('sku');
                    },
                  ),
                  TextFormField(
                    controller: _name,
                    decoration: const InputDecoration(labelText: 'Tên học cụ *'),
                    maxLength: 200,
                    validator: (v) => (v?.trim().isEmpty ?? true) ? 'Nhập tên học cụ' : _serverError('name'),
                  ),
                  TextFormField(
                    controller: _price,
                    keyboardType: TextInputType.number,
                    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                    decoration: const InputDecoration(labelText: 'Giá (VND) *', suffixText: '₫'),
                    validator: (v) => (v == null || v.isEmpty) ? 'Nhập giá' : _serverError('price'),
                  ),
                  TextFormField(
                    controller: _desc,
                    maxLines: 4,
                    maxLength: 4000,
                    decoration: const InputDecoration(labelText: 'Mô tả'),
                  ),
                  TextFormField(
                    controller: _image,
                    keyboardType: TextInputType.url,
                    decoration: const InputDecoration(labelText: 'Link ảnh'),
                    validator: (v) {
                      final s = v?.trim() ?? '';
                      if (s.isNotEmpty && !(Uri.tryParse(s)?.hasScheme ?? false)) {
                        return 'Link ảnh phải bắt đầu bằng http(s)://';
                      }
                      return null;
                    },
                  ),
                  if (_image.text.trim().isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      child: Center(child: SupplyImage(url: _image.text.trim(), size: 120)),
                    ),
                  Row(
                    children: [
                      Expanded(
                        child: TextFormField(
                          controller: _threshold,
                          keyboardType: TextInputType.number,
                          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                          decoration: const InputDecoration(
                            labelText: 'Báo sắp hết khi còn ≤',
                            helperText: 'Ngưỡng cảnh báo',
                          ),
                        ),
                      ),
                      if (!_isEdit) ...[
                        const SizedBox(width: 16),
                        Expanded(
                          child: TextFormField(
                            controller: _initialStock,
                            keyboardType: TextInputType.number,
                            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                            decoration: const InputDecoration(
                              labelText: 'Tồn đầu',
                              helperText: 'Ghi thành 1 lần nhập kho',
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 8),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Hiện trên cửa hàng'),
                    subtitle: const Text('Tắt để ẩn (vd. học cụ chỉ dùng làm quà kèm khóa học)'),
                    value: _active,
                    onChanged: (v) => setState(() => _active = v),
                  ),
                  if (_isEdit)
                    const Padding(
                      padding: EdgeInsets.only(top: 4),
                      child: Text(
                        'Số tồn không sửa ở đây. Dùng "Nhập kho" ở màn chi tiết sản phẩm.',
                        style: TextStyle(fontSize: 12),
                      ),
                    ),
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: _saving ? null : _save,
                    child: _saving
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : Text(_isEdit ? 'Lưu thay đổi' : 'Tạo học cụ'),
                  ),
                ],
              ),
            ),
    );
  }
}
