import 'package:flutter/material.dart';

import '../../../core/network/api_exceptions.dart';
import '../../../core/utils/format.dart';
import '../../../core/widgets/app_image.dart';
import '../../../core/widgets/price_text.dart';
import '../../../core/widgets/small_badge.dart';
import '../../../core/widgets/state_views.dart';
import '../../../core/widgets/video_player_page.dart';
import '../../cart/cart_gateway.dart';
import '../data/course_repository.dart';
import '../models/course_models.dart';

/// Chi tiết khóa học cho học viên: thông tin, quà tặng kèm, danh sách module/bài, học thử.
class CourseDetailPage extends StatefulWidget {
  const CourseDetailPage({super.key, required this.courseId});

  final int courseId;

  @override
  State<CourseDetailPage> createState() => _CourseDetailPageState();
}

class _CourseDetailPageState extends State<CourseDetailPage> {
  final _repo = CourseRepository();
  CourseDetail? _detail;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final detail = await _repo.detail(widget.courseId);
      if (!mounted) return;
      setState(() {
        _detail = detail;
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

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return Scaffold(appBar: AppBar(), body: const LoadingView());
    }
    if (_error != null || _detail == null) {
      return Scaffold(appBar: AppBar(), body: ErrorView(message: _error ?? 'Có lỗi xảy ra', onRetry: _load));
    }
    final detail = _detail!;
    final course = detail.course;

    return Scaffold(
      bottomNavigationBar: _buildBottomBar(course),
      body: CustomScrollView(
        slivers: [
          SliverAppBar(
            pinned: true,
            expandedHeight: 220,
            flexibleSpace: FlexibleSpaceBar(
              background: AppImage(url: course.thumbnailUrl, aspectRatio: 16 / 9),
            ),
          ),
          SliverPadding(
            padding: const EdgeInsets.all(16),
            sliver: SliverList(
              delegate: SliverChildListDelegate([
                _buildHeader(course),
                if (detail.description != null && detail.description!.isNotEmpty) ...[
                  const SizedBox(height: 20),
                  _sectionTitle('Giới thiệu'),
                  Text(detail.description!),
                ],
                if (detail.kits.isNotEmpty) ...[
                  const SizedBox(height: 20),
                  _sectionTitle('Dụng cụ tặng kèm miễn phí'),
                  ...detail.kits.map(_buildKit),
                ],
                const SizedBox(height: 20),
                _sectionTitle('Nội dung khóa học'),
                if (detail.modules.isEmpty) const Text('Khóa học chưa có bài học.'),
                ...detail.modules.map(_buildModule),
                const SizedBox(height: 16),
              ]),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHeader(CourseSummary course) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(course.title, style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold)),
        if (course.shortDescription != null && course.shortDescription!.isNotEmpty) ...[
          const SizedBox(height: 6),
          Text(course.shortDescription!, style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.outline)),
        ],
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 6,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            SmallBadge(
              text: levelLabel(course.level),
              color: course.level == 'ADVANCED' ? Colors.deepPurple : theme.colorScheme.primary,
            ),
            Text('${course.lessonCount} bài · ${formatDuration(course.totalDurationSeconds)}'),
            if (course.hasKits)
              const SmallBadge(text: 'Tặng kèm dụng cụ', color: Color(0xFFE65100), icon: Icons.card_giftcard),
          ],
        ),
        const SizedBox(height: 12),
        PriceText(price: course.price, discountPrice: course.discountPrice, large: true),
      ],
    );
  }

  Widget _sectionTitle(String text) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(text, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
      );

  Widget _buildKit(Kit kit) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: SizedBox(
          width: 56,
          child: AppImage(url: kit.thumbnailUrl, aspectRatio: 1, borderRadius: 8, icon: Icons.brush_outlined),
        ),
        title: Text(kit.name),
        subtitle: Text('Mã ${kit.sku}'),
        trailing: Text('x${kit.quantity}', style: const TextStyle(fontWeight: FontWeight.bold)),
      ),
    );
  }

  Widget _buildModule(PublicModule module) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        initiallyExpanded: true,
        shape: const Border(),
        collapsedShape: const Border(),
        title: Text(module.title, style: const TextStyle(fontWeight: FontWeight.w600)),
        subtitle: Text('${module.lessons.length} bài'),
        children: module.lessons.map(_buildLesson).toList(),
      ),
    );
  }

  Widget _buildLesson(PublicLesson lesson) {
    final canWatch = lesson.preview && lesson.previewVideoUrl != null;
    return ListTile(
      leading: Icon(
        canWatch ? Icons.play_circle_fill : Icons.lock_outline,
        color: canWatch ? Theme.of(context).colorScheme.primary : Theme.of(context).colorScheme.outline,
      ),
      title: Text(lesson.title),
      // Nhãn đặt ở dòng phụ để tên bài học không bị bóp hẹp trên màn hình nhỏ
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Wrap(
          spacing: 6,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(
              [
                formatDuration(lesson.durationSeconds),
                if (lesson.hasDocument) 'Có tài liệu',
              ].join(' · '),
              style: Theme.of(context).textTheme.bodySmall,
            ),
            if (lesson.preview) SmallBadge(text: 'Học thử', color: Colors.green.shade700),
            if (lesson.required) SmallBadge(text: 'Bắt buộc', color: Colors.orange.shade800),
          ],
        ),
      ),
      onTap: () {
        if (canWatch) {
          Navigator.of(context).push(MaterialPageRoute(
            builder: (_) => VideoPlayerPage(title: lesson.title, url: lesson.previewVideoUrl!),
          ));
        } else {
          showMessage(context, 'Hãy mua khóa học để xem bài này');
        }
      },
    );
  }

  Widget _buildBottomBar(CourseSummary course) {
    return SafeArea(
      child: Container(
        padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
        decoration: BoxDecoration(
          color: Colors.white,
          border: Border(top: BorderSide(color: Theme.of(context).colorScheme.outlineVariant)),
        ),
        child: Row(
          children: [
            Expanded(child: PriceText(price: course.price, discountPrice: course.discountPrice)),
            const SizedBox(width: 12),
            FilledButton.icon(
              // Qua CartGateway: D sẽ thay bằng giỏ hàng thật, màn hình này không phải sửa
              onPressed: () => cartGateway.addCourse(context, courseId: course.id, title: course.title),
              icon: const Icon(Icons.add_shopping_cart),
              label: const Text('Thêm vào giỏ'),
            ),
          ],
        ),
      ),
    );
  }
}
