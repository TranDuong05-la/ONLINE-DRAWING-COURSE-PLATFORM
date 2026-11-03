import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';

import '../../core/constants/app_colors.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import 'learning_widgets.dart';
import 'lesson_screen.dart';

/// Danh sách bài của khóa đã mua theo module (phần B), kèm tiến độ, bài bắt buộc và trạng thái bài tập.
class CourseDetailScreen extends StatefulWidget {
  const CourseDetailScreen({super.key, required this.courseId});

  final int courseId;

  @override
  State<CourseDetailScreen> createState() => _CourseDetailScreenState();
}

class _CourseDetailScreenState extends State<CourseDetailScreen> {
  final _repo = LearningRepository();
  int _reloadKey = 0;

  Future<void> _openLesson(int lessonId) async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => LessonScreen(lessonId: lessonId)));
    if (mounted) setState(() => _reloadKey++);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(title: const Text('Nội dung khóa học'), elevation: 0),
      body: AsyncBody<CourseDetail>(
        key: ValueKey(_reloadKey),
        load: () => _repo.getCourseDetail(widget.courseId),
        builder: (context, course, reload) => RefreshIndicator(
          onRefresh: reload,
          color: AppColors.primary,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _header(course),
              for (final module in course.modules) ...[
                const SizedBox(height: 20),
                Text(module.title,
                    style: GoogleFonts.plusJakartaSans(
                        fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.primaryDark)),
                const SizedBox(height: 8),
                for (final lesson in module.lessons) _lessonTile(lesson),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _header(CourseDetail course) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(course.title,
            style: GoogleFonts.plusJakartaSans(fontSize: 22, fontWeight: FontWeight.w800, color: AppColors.primaryDark)),
        if (course.description != null) ...[
          const SizedBox(height: 6),
          Text(course.description!,
              style: GoogleFonts.plusJakartaSans(fontSize: 13.5, color: AppColors.textSecondary, height: 1.4)),
        ],
        const SizedBox(height: 16),
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: LinearProgressIndicator(
            value: course.progressPercent / 100,
            minHeight: 8,
            backgroundColor: AppColors.surfaceMuted,
            valueColor: AlwaysStoppedAnimation(course.completed ? AppColors.success : AppColors.primary),
          ),
        ),
        const SizedBox(height: 6),
        Text('Tiến độ ${course.progressPercent}%',
            style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary)),
        const SizedBox(height: 8),
        // Điều kiện hoàn thành: đủ bài học bắt buộc và mọi bài tập bắt buộc được duyệt.
        Wrap(spacing: 8, runSpacing: 6, children: [
          StatusPill(
            label: 'Bài bắt buộc: ${course.completedRequiredLessons}/${course.requiredLessons}',
            color: course.completedRequiredLessons >= course.requiredLessons ? AppColors.success : AppColors.primary,
            icon: Icons.menu_book_outlined,
          ),
          if (course.requiredAssignments > 0)
            StatusPill(
              label: 'Bài tập đạt: ${course.approvedAssignments}/${course.requiredAssignments}',
              color: course.approvedAssignments >= course.requiredAssignments ? AppColors.success : AppColors.info,
              icon: Icons.assignment_turned_in_outlined,
            ),
        ]),
        const SizedBox(height: 12),
        if (course.completed)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(color: AppColors.successLight, borderRadius: BorderRadius.circular(14)),
            child: Row(
              children: [
                const Icon(Icons.workspace_premium_rounded, color: AppColors.success),
                const SizedBox(width: 10),
                Expanded(
                  child: Text('Bạn đã hoàn thành khóa học và được cấp chứng nhận.',
                      style: GoogleFonts.plusJakartaSans(fontSize: 13, fontWeight: FontWeight.w600)),
                ),
                TextButton(
                  onPressed: () => context.push('/profile/certificates'),
                  child: const Text('Xem'),
                ),
              ],
            ),
          )
        else if (course.continueLessonId != null)
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              onPressed: () => _openLesson(course.continueLessonId!),
              icon: const Icon(Icons.play_arrow_rounded),
              label: const Text('Tiếp tục bài đang học'),
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.primary,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
            ),
          ),
      ],
    );
  }

  Widget _lessonTile(LessonItem lesson) {
    final duration = formatSeconds(lesson.durationSeconds);
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(12),
      ),
      child: ListTile(
        onTap: () => _openLesson(lesson.id),
        leading: Icon(
          lesson.completed ? Icons.check_circle_rounded : Icons.play_circle_outline_rounded,
          color: lesson.completed ? AppColors.success : AppColors.primary,
          size: 28,
        ),
        title: Text(lesson.title,
            style: GoogleFonts.plusJakartaSans(fontSize: 14, fontWeight: FontWeight.w600)),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Wrap(
            spacing: 8,
            runSpacing: 4,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              if (duration.isNotEmpty)
                Text(duration, style: GoogleFonts.plusJakartaSans(fontSize: 12, color: AppColors.textSecondary)),
              if (!lesson.completed && lesson.lastPositionSeconds > 0)
                Text('Đã xem đến ${formatSeconds(lesson.lastPositionSeconds)}',
                    style: GoogleFonts.plusJakartaSans(fontSize: 12, color: AppColors.primary)),
              if (lesson.required)
                const StatusPill(label: 'Bắt buộc', color: Color(0xFF8E7CC3)),
              if (lesson.hasAssignment)
                StatusPill(
                  label: 'Bài tập${lesson.assignmentRequired ? ' bắt buộc' : ''}: '
                      '${assignmentStatusLabel(lesson.assignmentStatus)}',
                  color: assignmentStatusColor(lesson.assignmentStatus),
                ),
            ],
          ),
        ),
        trailing: const Icon(Icons.chevron_right),
      ),
    );
  }
}
