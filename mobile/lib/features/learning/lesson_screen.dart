import 'dart:async';

import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:video_player/video_player.dart';

import '../../core/constants/app_colors.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import 'assignment_screen.dart';
import 'learning_widgets.dart';

/// Plays a lesson video and saves progress.
///
/// Progress is reported every [_reportEvery] while playing, on pause, at the end, when the app goes to the
/// background and when the screen closes. Each report carries the resume position and the seconds actually
/// played since the last successful report; the server credits only played time, so seeking forward does
/// not count. A lesson is completed once 90% of the video has been watched.
class LessonScreen extends StatefulWidget {
  const LessonScreen({super.key, required this.lessonId});

  final int lessonId;

  @override
  State<LessonScreen> createState() => _LessonScreenState();
}

class _LessonScreenState extends State<LessonScreen> with WidgetsBindingObserver {
  static const _reportEvery = Duration(seconds: 15);

  final _repo = LearningRepository();
  LessonContent? _lesson;
  String? _loadError;
  VideoPlayerController? _controller;
  String? _videoError;
  bool _completed = false;
  int? _coursePercent;

  final _played = Stopwatch();
  int _creditedMs = 0;
  int _lastReportedPosition = -1;
  bool _reporting = false;
  bool _ended = false;
  DateTime? _lastWarning;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loadError = null;
      _videoError = null;
    });
    try {
      final lesson = await _repo.getLesson(widget.lessonId);
      if (!mounted) return;
      setState(() {
        _lesson = lesson;
        _completed = lesson.completed;
      });
      await _initVideo(lesson);
    } catch (e) {
      if (mounted) setState(() => _loadError = e.toString());
    }
  }

  Future<void> _initVideo(LessonContent lesson) async {
    _timer?.cancel();
    await _controller?.dispose();
    _controller = null;
    final url = lesson.videoUrl;
    if (url == null || url.isEmpty) {
      setState(() => _videoError = 'Bài học này không có video.');
      // Bài không có video: mở bài là được tính hoàn thành (server xét theo thời lượng = 0).
      if (!lesson.completed) {
        try {
          final res = await _repo.saveProgress(widget.lessonId, positionSeconds: 0, watchedDeltaSeconds: 0);
          if (mounted) {
            setState(() {
              _completed = res.completed;
              _coursePercent = res.courseProgressPercent;
            });
          }
        } catch (_) {}
      }
      return;
    }
    // Uploaded videos are served by our backend and need the login header; sample videos are public URLs.
    final local = url.startsWith('/');
    final c = VideoPlayerController.networkUrl(
      Uri.parse(local ? _repo.url(url) : url),
      httpHeaders: local ? await _repo.authHeaders() : const {},
    );
    try {
      await c.initialize();
    } catch (_) {
      await c.dispose();
      if (mounted) setState(() => _videoError = 'Không phát được video. Kiểm tra kết nối mạng rồi thử lại.');
      return;
    }
    if (!mounted) {
      await c.dispose();
      return;
    }
    final resume = lesson.lastPositionSeconds;
    final total = c.value.duration.inSeconds;
    // Restart from the beginning when the lesson was finished or the saved point is at the very end.
    if (!lesson.completed && resume > 0 && resume < total - 2) {
      await c.seekTo(Duration(seconds: resume));
    }
    _lastReportedPosition = resume;
    c.addListener(_onVideoTick);
    _timer = Timer.periodic(_reportEvery, (_) {
      if (_played.isRunning) _report();
    });
    setState(() => _controller = c);
  }

  void _onVideoTick() {
    final c = _controller;
    if (c == null) return;
    final v = c.value;
    if (v.hasError) {
      if (_videoError == null && mounted) setState(() => _videoError = 'Video bị lỗi khi phát. Vui lòng thử lại.');
      return;
    }
    if (v.isPlaying && !_played.isRunning) {
      _played.start();
    } else if (!v.isPlaying && _played.isRunning) {
      _played.stop();
      _report();
    }
    final atEnd = v.duration > Duration.zero && v.position >= v.duration - const Duration(milliseconds: 300);
    if (atEnd && !_ended) {
      _ended = true;
      _report();
    } else if (!atEnd) {
      _ended = false;
    }
  }

  /// Sends position + played seconds not yet credited. On failure nothing is lost: the uncredited time
  /// goes out with the next report.
  Future<void> _report({bool closing = false}) async {
    final c = _controller;
    if (c == null || _reporting) return;
    final position = c.value.position.inSeconds;
    final delta = ((_played.elapsedMilliseconds - _creditedMs) / 1000).floor().clamp(0, 300);
    if (delta == 0 && position == _lastReportedPosition) return;
    _reporting = true;
    try {
      final res = await _repo.saveProgress(widget.lessonId, positionSeconds: position, watchedDeltaSeconds: delta);
      _creditedMs += delta * 1000;
      _lastReportedPosition = position;
      if (!closing && mounted) {
        final justCompleted = res.completed && !_completed;
        setState(() {
          _completed = res.completed;
          _coursePercent = res.courseProgressPercent;
        });
        if (justCompleted) {
          showSnack(
            context,
            res.courseCompleted
                ? 'Chúc mừng! Bạn đã hoàn thành khóa học. Chứng nhận có trong mục Tài khoản › Chứng nhận của tôi.'
                : 'Đã hoàn thành bài học.',
          );
        }
      }
    } catch (_) {
      final now = DateTime.now();
      if (!closing && mounted && (_lastWarning == null || now.difference(_lastWarning!).inSeconds > 60)) {
        _lastWarning = now;
        showSnack(context, 'Chưa lưu được tiến độ, sẽ thử lại sau.', isError: true);
      }
    } finally {
      _reporting = false;
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      _controller?.pause();
      _report();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    final c = _controller;
    if (c != null) {
      _played.stop();
      _report(closing: true); // reads the controller before it is disposed below
      c.removeListener(_onVideoTick);
      Future.delayed(const Duration(milliseconds: 200), c.dispose);
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final lesson = _lesson;
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(title: Text(lesson?.title ?? 'Bài học'), elevation: 0),
      body: _loadError != null
          ? ErrorRetry(message: _loadError!, onRetry: _load)
          : lesson == null
              ? const Center(child: CircularProgressIndicator(valueColor: AlwaysStoppedAnimation(AppColors.primary)))
              : ListView(
                  children: [
                    _player(),
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(lesson.title,
                              style: GoogleFonts.plusJakartaSans(
                                  fontSize: 19, fontWeight: FontWeight.w800, color: AppColors.primaryDark)),
                          const SizedBox(height: 10),
                          Wrap(
                            spacing: 8,
                            runSpacing: 6,
                            children: [
                              _completed
                                  ? const StatusPill(
                                      label: 'Đã hoàn thành bài học', color: AppColors.success, icon: Icons.check_circle)
                                  : const StatusPill(
                                      label: 'Xem ít nhất 90% video để hoàn thành',
                                      color: AppColors.textSecondary,
                                      icon: Icons.info_outline),
                              if (_coursePercent != null)
                                StatusPill(label: 'Tiến độ khóa: $_coursePercent%', color: AppColors.primary),
                            ],
                          ),
                          if (lesson.description != null && lesson.description!.isNotEmpty) ...[
                            const SizedBox(height: 14),
                            Text(lesson.description!,
                                style: GoogleFonts.plusJakartaSans(
                                    fontSize: 14, color: AppColors.textSecondary, height: 1.45)),
                          ],
                          const SizedBox(height: 18),
                          if (lesson.hasAssignment)
                            SizedBox(
                              width: double.infinity,
                              child: ElevatedButton.icon(
                                onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                                  builder: (_) => AssignmentScreen(lessonId: lesson.id, lessonTitle: lesson.title),
                                )),
                                icon: const Icon(Icons.assignment_outlined),
                                label: const Text('Làm bài tập'),
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: AppColors.primary,
                                  foregroundColor: Colors.white,
                                  padding: const EdgeInsets.symmetric(vertical: 14),
                                ),
                              ),
                            ),
                          const SizedBox(height: 10),
                          Row(
                            children: [
                              if (lesson.materialUrl != null && lesson.materialUrl!.isNotEmpty)
                                Expanded(
                                  child: OutlinedButton.icon(
                                    onPressed: () => launchUrl(Uri.parse(_repo.url(lesson.materialUrl!)),
                                        mode: LaunchMode.externalApplication),
                                    icon: const Icon(Icons.attach_file),
                                    label: const Text('Tài liệu'),
                                  ),
                                ),
                              if (lesson.materialUrl != null && lesson.nextLessonId != null) const SizedBox(width: 10),
                              if (lesson.nextLessonId != null)
                                Expanded(
                                  child: OutlinedButton.icon(
                                    onPressed: () => Navigator.of(context).pushReplacement(MaterialPageRoute(
                                      builder: (_) => LessonScreen(lessonId: lesson.nextLessonId!),
                                    )),
                                    icon: const Icon(Icons.skip_next_rounded),
                                    label: const Text('Bài tiếp theo'),
                                    style: OutlinedButton.styleFrom(foregroundColor: AppColors.primary),
                                  ),
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

  Widget _player() {
    final c = _controller;
    if (_videoError != null) {
      return AspectRatio(
        aspectRatio: 16 / 9,
        child: ColoredBox(
          color: Colors.black,
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(_videoError!, style: const TextStyle(color: Colors.white), textAlign: TextAlign.center),
                  const SizedBox(height: 8),
                  ElevatedButton(onPressed: _load, child: const Text('Thử lại')),
                ],
              ),
            ),
          ),
        ),
      );
    }
    if (c == null) {
      return const AspectRatio(
        aspectRatio: 16 / 9,
        child: ColoredBox(color: Colors.black, child: Center(child: CircularProgressIndicator())),
      );
    }
    return Column(
      children: [
        AspectRatio(
          aspectRatio: c.value.aspectRatio,
          child: Stack(
            alignment: Alignment.center,
            children: [
              VideoPlayer(c),
              ValueListenableBuilder<VideoPlayerValue>(
                valueListenable: c,
                builder: (_, v, _) => IconButton(
                  iconSize: 60,
                  color: Colors.white,
                  icon: Icon(v.isPlaying ? Icons.pause_circle_filled : Icons.play_circle_filled),
                  onPressed: () {
                    if (v.isPlaying) {
                      c.pause();
                    } else {
                      if (_ended) c.seekTo(Duration.zero);
                      c.play();
                    }
                  },
                ),
              ),
            ],
          ),
        ),
        VideoProgressIndicator(
          c,
          allowScrubbing: true,
          colors: const VideoProgressColors(playedColor: AppColors.primary, bufferedColor: AppColors.primaryMint),
        ),
      ],
    );
  }
}
