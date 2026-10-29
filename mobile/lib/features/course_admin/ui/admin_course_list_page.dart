import 'dart:async';

import 'package:flutter/material.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/widgets/state_views.dart';
import '../../course/models/course_models.dart';
import '../../course/ui/course_card.dart';
import '../data/course_admin_repository.dart';
import 'admin_course_detail_page.dart';
import 'admin_course_form_page.dart';

/// Danh sách khóa học của admin: mọi trạng thái, lọc theo trạng thái, tìm theo tên.
class AdminCourseListPage extends StatefulWidget {
  const AdminCourseListPage({super.key});

  @override
  State<AdminCourseListPage> createState() => _AdminCourseListPageState();
}

class _AdminCourseListPageState extends State<AdminCourseListPage> with AutomaticKeepAliveClientMixin {
  final _repo = CourseAdminRepository();
  final _scroll = ScrollController();
  final _searchCtrl = TextEditingController();
  Timer? _debounce;

  final List<CourseSummary> _items = [];
  int _page = 0;
  bool _hasMore = true;
  bool _loading = false;
  String? _error;
  int _requestToken = 0;
  String? _status; // null = tất cả

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      if (_scroll.position.pixels >= _scroll.position.maxScrollExtent - 300) _load();
    });
    _load(reset: true);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _scroll.dispose();
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _load({bool reset = false}) async {
    if (reset) {
      _requestToken++;
      _loading = false;
    }
    if (_loading || (!reset && !_hasMore)) return;

    final token = _requestToken;
    final page = reset ? 0 : _page + 1;
    setState(() {
      _loading = true;
      _error = null;
      if (reset) {
        _items.clear();
        _hasMore = true;
        _page = 0;
      }
    });
    try {
      final result = await _repo.listCourses(status: _status, keyword: _searchCtrl.text, page: page);
      if (!mounted || token != _requestToken) return;
      setState(() {
        _items.addAll(result.items);
        _page = result.page;
        _hasMore = result.hasMore;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted || token != _requestToken) return;
      setState(() {
        _error = e.displayMessage;
        _loading = false;
      });
    }
  }

  Future<void> _openDetail(int id) async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => AdminCourseDetailPage(courseId: id)));
    if (mounted) _load(reset: true); // quay lại thì tải lại để thấy thay đổi
  }

  Future<void> _create() async {
    final created = await Navigator.of(context).push<int>(
      MaterialPageRoute(builder: (_) => const AdminCourseFormPage()),
    );
    if (!mounted || created == null) return;
    // Tạo xong mở luôn trang chi tiết để thêm module/bài học
    await _openDetail(created);
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        heroTag: null, // xem ghi chú ở admin_bundle_list_page.dart
        onPressed: _create,
        icon: const Icon(Icons.add),
        label: const Text('Thêm khóa học'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
            child: Column(
              children: [
                TextField(
                  controller: _searchCtrl,
                  onChanged: (_) {
                    _debounce?.cancel();
                    _debounce = Timer(const Duration(milliseconds: 400), () => _load(reset: true));
                    setState(() {});
                  },
                  decoration: InputDecoration(
                    hintText: 'Tìm khóa học theo tên',
                    prefixIcon: const Icon(Icons.search),
                    suffixIcon: _searchCtrl.text.isEmpty
                        ? null
                        : IconButton(
                            icon: const Icon(Icons.clear),
                            onPressed: () {
                              _searchCtrl.clear();
                              _load(reset: true);
                              setState(() {});
                            },
                          ),
                  ),
                ),
                const SizedBox(height: 8),
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      _statusChip('Tất cả', null),
                      const SizedBox(width: 8),
                      _statusChip('Nháp', 'DRAFT'),
                      const SizedBox(width: 8),
                      _statusChip('Đang bán', 'PUBLISHED'),
                      const SizedBox(width: 8),
                      _statusChip('Ngừng bán', 'HIDDEN'),
                    ],
                  ),
                ),
              ],
            ),
          ),
          Expanded(child: _buildBody()),
        ],
      ),
    );
  }

  Widget _statusChip(String label, String? value) {
    return ChoiceChip(
      label: Text(label),
      selected: _status == value,
      onSelected: (_) {
        setState(() => _status = value);
        _load(reset: true);
      },
    );
  }

  Widget _buildBody() {
    if (_items.isEmpty) {
      if (_loading) return const LoadingView();
      if (_error != null) return ErrorView(message: _error!, onRetry: () => _load(reset: true));
      return RefreshIndicator(
        onRefresh: () => _load(reset: true),
        child: LayoutBuilder(
          builder: (context, c) => ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            children: [
              SizedBox(height: c.maxHeight, child: const EmptyView(message: 'Chưa có khóa học nào')),
            ],
          ),
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: () => _load(reset: true),
      child: ListView.separated(
        controller: _scroll,
        physics: const AlwaysScrollableScrollPhysics(),
        // chừa chỗ phía dưới để nút "Thêm khóa học" không che thẻ cuối
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 88),
        itemCount: _items.length + 1,
        separatorBuilder: (_, _) => const SizedBox(height: 12),
        itemBuilder: (context, i) {
          if (i == _items.length) {
            return _loading
                ? const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator()))
                : (_error != null
                    ? TextButton(onPressed: _load, child: Text('$_error - Thử lại'))
                    : const SizedBox.shrink());
          }
          final course = _items[i];
          return CourseCard(course: course, showStatus: true, onTap: () => _openDetail(course.id));
        },
      ),
    );
  }
}
