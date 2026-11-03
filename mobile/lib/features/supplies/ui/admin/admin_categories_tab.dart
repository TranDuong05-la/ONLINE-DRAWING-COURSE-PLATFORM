import 'package:flutter/material.dart';

import '../widgets/supply_states.dart';
import '../../models/supply_models.dart';
import '../../data/supply_repository.dart';

class AdminCategoriesTab extends StatefulWidget {
  const AdminCategoriesTab({super.key});

  @override
  State<AdminCategoriesTab> createState() => _AdminCategoriesTabState();
}

class _AdminCategoriesTabState extends State<AdminCategoriesTab> with AutomaticKeepAliveClientMixin {
  late Future<List<SupplyCategory>> _future = SupplyRepository.instance.adminCategories();

  @override
  bool get wantKeepAlive => true;

  Future<void> _refresh() async {
    final f = SupplyRepository.instance.adminCategories();
    setState(() => _future = f);
    await f.catchError((_) => <SupplyCategory>[]);
  }

  Future<void> _edit([SupplyCategory? c]) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _CategoryDialog(category: c),
    );
    if (saved == true) _refresh();
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        heroTag: 'add-category',
        onPressed: () => _edit(),
        icon: const Icon(Icons.add),
        label: const Text('Thêm danh mục'),
      ),
      body: FutureBuilder<List<SupplyCategory>>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) return const LoadingView();
          if (snap.hasError) return ErrorView(error: snap.error!, onRetry: _refresh);
          final list = snap.data!;
          if (list.isEmpty) return const EmptyView(message: 'Chưa có danh mục');
          return RefreshIndicator(
            onRefresh: _refresh,
            child: ListView.separated(
              padding: const EdgeInsets.only(bottom: 96),
              itemCount: list.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final c = list[i];
                return ListTile(
                  leading: CircleAvatar(child: Text('${c.sortOrder}')),
                  title: Text(c.name),
                  subtitle: c.description == null ? null : Text(c.description!),
                  trailing: c.active
                      ? const Icon(Icons.visibility_outlined)
                      : const Icon(Icons.visibility_off_outlined, color: Colors.grey),
                  onTap: () => _edit(c),
                );
              },
            ),
          );
        },
      ),
    );
  }
}

class _CategoryDialog extends StatefulWidget {
  final SupplyCategory? category;
  const _CategoryDialog({this.category});

  @override
  State<_CategoryDialog> createState() => _CategoryDialogState();
}

class _CategoryDialogState extends State<_CategoryDialog> {
  late final _name = TextEditingController(text: widget.category?.name);
  late final _desc = TextEditingController(text: widget.category?.description);
  late final _order = TextEditingController(text: '${widget.category?.sortOrder ?? 0}');
  late bool _active = widget.category?.active ?? true;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _desc.dispose();
    _order.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_name.text.trim().isEmpty) {
      setState(() => _error = 'Nhập tên danh mục');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await SupplyRepository.instance.saveCategory(
        id: widget.category?.id,
        name: _name.text.trim(),
        description: _desc.text.trim().isEmpty ? null : _desc.text.trim(),
        active: _active,
        sortOrder: int.tryParse(_order.text) ?? 0,
      );
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = e.toString();
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.category == null ? 'Thêm danh mục' : 'Sửa danh mục'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _name,
              decoration: InputDecoration(labelText: 'Tên danh mục', errorText: _error),
            ),
            TextField(
              controller: _desc,
              decoration: const InputDecoration(labelText: 'Mô tả'),
            ),
            TextField(
              controller: _order,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Thứ tự hiển thị'),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Hiện trên cửa hàng'),
              value: _active,
              onChanged: (v) => setState(() => _active = v),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Hủy')),
        FilledButton(onPressed: _saving ? null : _save, child: const Text('Lưu')),
      ],
    );
  }
}
