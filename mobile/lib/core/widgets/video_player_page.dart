import 'package:chewie/chewie.dart';
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

import '../network/media_url.dart';

/// Màn phát video toàn màn hình (dùng cho học thử và xem trước của admin).
/// [url] có thể là đường dẫn tương đối ("/uploads/a.mp4") hoặc link ngoài.
class VideoPlayerPage extends StatefulWidget {
  const VideoPlayerPage({super.key, required this.title, required this.url});

  final String title;
  final String url;

  @override
  State<VideoPlayerPage> createState() => _VideoPlayerPageState();
}

class _VideoPlayerPageState extends State<VideoPlayerPage> {
  VideoPlayerController? _video;
  ChewieController? _chewie;
  String? _error;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    try {
      final video = VideoPlayerController.networkUrl(Uri.parse(resolveMediaUrl(widget.url)));
      _video = video;
      await video.initialize();
      if (!mounted) return;
      setState(() {
        _chewie = ChewieController(
          videoPlayerController: video,
          autoPlay: true,
          aspectRatio: video.value.aspectRatio,
          // Chewie tự có thanh tua, nút tạm dừng và nút toàn màn hình
        );
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = 'Không phát được video. Kiểm tra mạng và đường dẫn video.');
    }
  }

  @override
  void dispose() {
    // Phải giải phóng, nếu không video vẫn chạy ngầm và tốn pin/RAM
    _chewie?.dispose();
    _video?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: Text(widget.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        surfaceTintColor: Colors.black,
      ),
      body: Center(
        child: _error != null
            ? Padding(
                padding: const EdgeInsets.all(24),
                child: Text(_error!, style: const TextStyle(color: Colors.white), textAlign: TextAlign.center),
              )
            : _chewie == null
                ? const CircularProgressIndicator()
                : Chewie(controller: _chewie!),
      ),
    );
  }
}
