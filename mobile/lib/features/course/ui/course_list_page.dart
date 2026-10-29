import 'dart:async';

import 'package:flutter/material.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/widgets/state_views.dart';
import '../data/course_repository.dart';
import '../models/course_models.dart';
import 'course_card.dart';
import 'course_detail_page.dart';

/// Danh sách khóa học cho học viên: tìm theo tên, lọc cấp độ, sắp xếp, cuộn xuống để tải thêm.
class CourseListPage extends StatefulWidget {
  const CourseListPage({super.key});

  @override
  State<CourseListPage> createState() => _CourseListPageState();
}

class _CourseListPageState extends State<CourseListPage> with AutomaticKeepAliveClientMixin {
  static const _sortOptions = {
    'newest': 'Mới nhất',
    'price_asc': 'Giá tăng dần',
    'price_desc': 'Giá giảm dần',
  };

  final _repo = CourseRepository();
  final _scroll = ScrollController();
  final _searchCtrl = TextEditingController();
  Timer? _debounce;

  final List<CourseSummary> _items = [];
  int _page = 0;
  bool _hasMore = true;
  bool _loading = false;
  String? _error;
  int _requestToken = 0; // để bỏ qua kết quả của yêu cầu cũ khi người dùng đổi bộ lọc liên tục

  String? _level; // null = tất cả
  String _sort = 'newest';

  @override
  bool get wantKeepAlive => true; // giữ trạng thái khi chuyển tab

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    _load(reset: true);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _scroll.dispose();
    _searchCtrl.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scroll.position.pixels >= _scroll.position.maxScrollExtent - 300) {
      _load();
    }
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
      final result = await _repo.list(
        keyword: _searchCtrl.text,
        level: _level,
        sort: _sort,
        page: page,
      );
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

  void _onSearchChanged(String _) {
    // Chờ người dùng gõ xong 0,4 giây mới gọi API để đỡ gọi liên tục
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () => _load(reset: true));
    setState(() {}); // để nút xóa (x) hiện/ẩn
  }

  @override
  Widget build(BuildContext context) {
    super.build(context); // bắt buộc khi dùng AutomaticKeepAliveClientMixin
    return Column(
      children: [
        _buildFilters(),
        Expanded(child: _buildBody()),
      ],
    );
  }

  Widget _buildFilters() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      child: Column(
        children: [
          TextField(
            controller: _searchCtrl,
            onChanged: _onSearchChanged,
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _load(reset: true),
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
          Row(
            children: [
              Expanded(
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      _levelChip('Tất cả', null),
                      const SizedBox(width: 8),
                      _levelChip('Cơ bản', 'BASIC'),
                      const SizedBox(width: 8),
                      _levelChip('Nâng cao', 'ADVANCED'),
                    ],
                  ),
                ),
              ),
              PopupMenuButton<String>(
                tooltip: 'Sắp xếp',
                initialValue: _sort,
                icon: const Icon(Icons.sort),
                onSelected: (value) {
                  setState(() => _sort = value);
                  _load(reset: true);
                },
                itemBuilder: (_) => [
                  for (final entry in _sortOptions.entries)
                    PopupMenuItem(value: entry.key, child: Text(entry.value)),
                ],
              ),
            ],
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: Text(
              'Sắp xếp: ${_sortOptions[_sort]}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ],
      ),
    );
  }

  Widget _levelChip(String label, String? value) {
    return ChoiceChip(
      label: Text(label),
      selected: _level == value,
      onSelected: (_) {
        setState(() => _level = value);
        _load(reset: true);
      },
    );
  }

  Widget _buildBody() {
    if (_items.isEmpty) {
      if (_loading) return const LoadingView();
      if (_error != null) {
        return ErrorView(message: _error!, onRetry: () => _load(reset: true));
      }
      return _refreshable(const EmptyView(message: 'Không tìm thấy khóa học phù hợp'));
    }

    return RefreshIndicator(
      onRefresh: () => _load(reset: true),
      child: ListView.separated(
        controller: _scroll,
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        itemCount: _items.length + 1,
        separatorBuilder: (_, _) => const SizedBox(height: 12),
        itemBuilder: (context, index) {
          if (index == _items.length) return _buildFooter();
          final course = _items[index];
          return CourseCard(
            course: course,
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => CourseDetailPage(courseId: course.id)),
            ),
          );
        },
      ),
    );
  }

  /// Cuối danh sách: vòng xoay khi đang tải thêm, hoặc thông báo lỗi có nút thử lại.
  Widget _buildFooter() {
    if (_loading) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (_error != null) {
      return Column(
        children: [
          Text(_error!, textAlign: TextAlign.center),
          TextButton(onPressed: _load, child: const Text('Thử lại')),
        ],
      );
    }
    return const SizedBox(height: 8);
  }

  /// Bọc nội dung ngắn trong ListView để vẫn kéo xuống làm mới được.
  Widget _refreshable(Widget child) {
    return RefreshIndicator(
      onRefresh: () => _load(reset: true),
      child: LayoutBuilder(
        builder: (context, constraints) => ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [SizedBox(height: constraints.maxHeight, child: child)],
        ),
      ),
    );
  }
}
