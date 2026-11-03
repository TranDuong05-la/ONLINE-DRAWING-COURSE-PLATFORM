import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';

import '../../core/constants/app_colors.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import 'assignment_screen.dart';
import 'course_detail_screen.dart';
import 'learning_widgets.dart';
import 'lesson_screen.dart';

/// Chuông thông báo ở trang chủ: hiện số thông báo chưa đọc, tự cập nhật mỗi 30 giây và khi quay lại màn hình.
class NotificationBell extends StatefulWidget {
  const NotificationBell({super.key});

  @override
  State<NotificationBell> createState() => _NotificationBellState();
}

class _NotificationBellState extends State<NotificationBell> with WidgetsBindingObserver {
  final _repo = LearningRepository();
  int _unread = 0;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 30), (_) => _refresh());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      final n = await _repo.getUnreadCount();
      if (mounted && n != _unread) setState(() => _unread = n);
    } catch (_) {
      // không có mạng / chưa đăng nhập: giữ số cũ
    }
  }

  Future<void> _open() async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => const NotificationsScreen()));
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: _open,
      customBorder: const CircleBorder(),
      child: Stack(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: const BoxDecoration(color: AppColors.surfaceMuted, shape: BoxShape.circle),
            child: const Icon(Icons.notifications_none_rounded, color: AppColors.textPrimary, size: 24),
          ),
          if (_unread > 0)
            Positioned(
              top: 2,
              right: 2,
              child: Container(
                constraints: const BoxConstraints(minWidth: 18, minHeight: 18),
                padding: const EdgeInsets.symmetric(horizontal: 4),
                decoration: BoxDecoration(color: AppColors.accentPeach, borderRadius: BorderRadius.circular(9)),
                child: Center(
                  child: Text(_unread > 99 ? '99+' : '$_unread',
                      style: GoogleFonts.plusJakartaSans(color: Colors.white, fontSize: 10, fontWeight: FontWeight.w700)),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Danh sách thông báo. Bấm một thông báo: đánh dấu đã đọc rồi mở đúng màn hình liên quan.
class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key});

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  final _repo = LearningRepository();
  int _reloadKey = 0;

  Future<void> _markAll() async {
    try {
      await _repo.markAllNotificationsRead();
      if (mounted) setState(() => _reloadKey++);
    } catch (e) {
      if (mounted) showSnack(context, e.toString(), isError: true);
    }
  }

  Future<void> _open(NotificationItem n) async {
    if (!n.read) {
      _repo.markNotificationRead(n.id).catchError((_) {});
    }
    final Widget? screen = switch (n.type) {
      'NEW_ASSIGNMENT' || 'GRADED_APPROVED' || 'GRADED_REVISION' when n.lessonId != null =>
        AssignmentScreen(lessonId: n.lessonId!),
      'NEW_LESSON_VIDEO' when n.lessonId != null => LessonScreen(lessonId: n.lessonId!),
      'COURSE_ENROLLED' when n.courseId != null => CourseDetailScreen(courseId: n.courseId!),
      _ => null,
    };
    if (n.type == 'CERTIFICATE') {
      await context.push('/profile/certificates');
    } else if (screen != null) {
      await Navigator.of(context).push(MaterialPageRoute(builder: (_) => screen));
    }
    if (mounted) setState(() => _reloadKey++);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        title: const Text('Thông báo'),
        actions: [TextButton(onPressed: _markAll, child: const Text('Đọc tất cả'))],
      ),
      body: AsyncBody<List<NotificationItem>>(
        key: ValueKey(_reloadKey),
        load: _repo.getNotifications,
        builder: (context, items, reload) => RefreshIndicator(
          onRefresh: reload,
          child: items.isEmpty
              ? ListView(children: [
                  const SizedBox(height: 120),
                  const Icon(Icons.notifications_off_outlined, size: 56, color: AppColors.textMuted),
                  const SizedBox(height: 12),
                  Text('Chưa có thông báo nào',
                      textAlign: TextAlign.center, style: GoogleFonts.plusJakartaSans(color: AppColors.textSecondary)),
                ])
              : ListView.separated(
                  itemCount: items.length,
                  separatorBuilder: (_, _) => const Divider(height: 1),
                  itemBuilder: (_, i) => _tile(items[i]),
                ),
        ),
      ),
    );
  }

  Widget _tile(NotificationItem n) {
    final (icon, color) = switch (n.type) {
      'CERTIFICATE' => (Icons.workspace_premium_rounded, AppColors.success),
      'GRADED_APPROVED' => (Icons.check_circle_outline, AppColors.success),
      'GRADED_REVISION' => (Icons.edit_note_rounded, AppColors.warning),
      'NEW_ASSIGNMENT' => (Icons.assignment_outlined, const Color(0xFF8E7CC3)),
      'NEW_LESSON_VIDEO' => (Icons.ondemand_video_rounded, AppColors.info),
      'COURSE_ENROLLED' => (Icons.school_outlined, AppColors.primary),
      _ => (Icons.notifications_none_rounded, AppColors.textSecondary),
    };
    final time = n.createdAt == null ? '' : DateFormat('dd/MM HH:mm').format(n.createdAt!);
    return Material(
      color: n.read ? Colors.white : AppColors.primaryMintLight,
      child: ListTile(
        onTap: () => _open(n),
        leading: CircleAvatar(backgroundColor: color.withValues(alpha: 0.12), child: Icon(icon, color: color)),
        title: Text(n.title,
            style: GoogleFonts.plusJakartaSans(fontSize: 14, fontWeight: n.read ? FontWeight.w500 : FontWeight.w700)),
        subtitle: Text('${n.body ?? ''}${n.body == null ? '' : '\n'}$time',
            style: GoogleFonts.plusJakartaSans(fontSize: 12.5, color: AppColors.textSecondary)),
        isThreeLine: n.body != null,
        trailing: n.read ? null : const Icon(Icons.circle, size: 10, color: AppColors.accentPeach),
      ),
    );
  }
}
