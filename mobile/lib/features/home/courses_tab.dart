import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import '../../core/constants/app_colors.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import '../cart/course_ownership_refresh_signal.dart';
import '../bundle/ui/bundle_list_page.dart';
import '../course/ui/course_list_page.dart';
import '../learning/course_detail_screen.dart';
import '../learning/learning_widgets.dart';
import '../learning/lesson_screen.dart';

/// Tab "Khóa học" của MainScreen:
/// - "Khóa của tôi" (phần C): khóa đã mua, tiến độ, nút tiếp tục học.
/// - "Từng khóa" và "Combo" (phần B): danh mục khóa học / combo thật từ /api/courses, /api/bundles.
class CoursesTab extends StatelessWidget {
  const CoursesTab({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Khóa học'),
          bottom: TabBar(
            labelColor: AppColors.primaryDark,
            unselectedLabelColor: AppColors.textSecondary,
            indicatorColor: AppColors.primary,
            labelStyle: GoogleFonts.plusJakartaSans(
              fontWeight: FontWeight.w700,
              fontSize: 14,
            ),
            tabs: const [
              Tab(text: 'Khóa của tôi'),
              Tab(text: 'Từng khóa'),
              Tab(text: 'Combo'),
            ],
          ),
        ),
        body: const TabBarView(
          children: [_MyCoursesView(), CourseListPage(), BundleListPage()],
        ),
      ),
    );
  }
}
// ---------------------------------------------------------------- my courses

class _MyCoursesView extends StatefulWidget {
  const _MyCoursesView();

  @override
  State<_MyCoursesView> createState() => _MyCoursesViewState();
}

class _MyCoursesViewState extends State<_MyCoursesView>
    with AutomaticKeepAliveClientMixin {
  final _repo = LearningRepository();
  int _reloadKey = 0;

  @override
  void initState() {
    super.initState();
    CourseOwnershipRefreshSignal.revision.addListener(_refreshAfterPurchase);
  }

  @override
  void dispose() {
    CourseOwnershipRefreshSignal.revision.removeListener(_refreshAfterPurchase);
    super.dispose();
  }

  void _refreshAfterPurchase() {
    if (mounted) setState(() => _reloadKey++);
  }

  @override
  bool get wantKeepAlive => true;

  Future<void> _open(Widget screen) async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => screen));
    if (mounted) {
      setState(() => _reloadKey++); // refresh progress after learning
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return AsyncBody<List<MyCourse>>(
      key: ValueKey(_reloadKey),
      load: _repo.getMyCourses,
      builder: (context, courses, reload) {
        if (courses.isEmpty) {
          return RefreshIndicator(
            onRefresh: reload,
            color: AppColors.primary,
            child: ListView(
              children: [
                const SizedBox(height: 80),
                const Icon(
                  Icons.school_outlined,
                  size: 64,
                  color: AppColors.textMuted,
                ),
                const SizedBox(height: 12),
                Text(
                  'Bạn chưa có khóa học nào',
                  textAlign: TextAlign.center,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'Xem tab "Từng khóa" hoặc "Combo" để chọn khóa học phù hợp.',
                  textAlign: TextAlign.center,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 13,
                    color: AppColors.textSecondary,
                  ),
                ),
              ],
            ),
          );
        }
        final learning = courses
            .where((c) => !c.completed && c.continueLessonId != null)
            .toList();
        return RefreshIndicator(
          onRefresh: reload,
          color: AppColors.primary,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (learning.isNotEmpty) ...[
                _ContinueCard(
                  course: learning.first,
                  onTap: () => _open(
                    LessonScreen(lessonId: learning.first.continueLessonId!),
                  ),
                ),
                const SizedBox(height: 18),
              ],
              for (final c in courses)
                _MyCourseCard(
                  course: c,
                  onTap: () => _open(CourseDetailScreen(courseId: c.courseId)),
                ),
            ],
          ),
        );
      },
    );
  }
}

class _ContinueCard extends StatelessWidget {
  const _ContinueCard({required this.course, required this.onTap});

  final MyCourse course;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(18),
      child: Container(
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            colors: [AppColors.primaryDark, AppColors.primaryMedium],
          ),
          borderRadius: BorderRadius.circular(18),
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'TIẾP TỤC HỌC',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                      color: AppColors.accentYellow,
                      letterSpacing: 1,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    course.title,
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 17,
                      fontWeight: FontWeight.w700,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 10),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(6),
                    child: LinearProgressIndicator(
                      value: course.progressPercent / 100,
                      minHeight: 6,
                      backgroundColor: Colors.white24,
                      valueColor: const AlwaysStoppedAnimation(
                        AppColors.accentYellow,
                      ),
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '${course.completedLessons}/${course.totalLessons} bài • ${course.progressPercent}%',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 12,
                      color: Colors.white70,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Container(
              width: 48,
              height: 48,
              decoration: const BoxDecoration(
                color: AppColors.accentYellow,
                shape: BoxShape.circle,
              ),
              child: const Icon(
                Icons.play_arrow_rounded,
                color: AppColors.primaryDark,
                size: 30,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _MyCourseCard extends StatelessWidget {
  const _MyCourseCard({required this.course, required this.onTap});

  final MyCourse course;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return _CardShell(
      onTap: onTap,
      child: Row(
        children: [
          CourseCover(seed: course.courseId, thumbnailUrl: course.thumbnailUrl),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  course.title,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                    color: AppColors.primaryDark,
                  ),
                ),
                const SizedBox(height: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(6),
                  child: LinearProgressIndicator(
                    value: course.progressPercent / 100,
                    minHeight: 6,
                    backgroundColor: AppColors.surfaceMuted,
                    valueColor: AlwaysStoppedAnimation(
                      course.completed ? AppColors.success : AppColors.primary,
                    ),
                  ),
                ),
                const SizedBox(height: 6),
                Row(
                  children: [
                    Text(
                      '${course.completedLessons}/${course.totalLessons} bài • ${course.progressPercent}%',
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                    ),
                    const Spacer(),
                    if (course.completed)
                      const StatusPill(
                        label: 'Hoàn thành',
                        color: AppColors.success,
                        icon: Icons.verified,
                      ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _CardShell extends StatelessWidget {
  const _CardShell({required this.child, required this.onTap});

  final Widget child;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Material(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(16),
          child: Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: AppColors.border),
            ),
            child: child,
          ),
        ),
      ),
    );
  }
}
