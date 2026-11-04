import 'package:flutter_test/flutter_test.dart';
import 'package:artnest_app/core/network/video_link.dart';

void main() {
  group('youtubeVideoId', () {
    test('nhận các dạng link YouTube phổ biến', () {
      expect(youtubeVideoId('https://www.youtube.com/watch?v=dQw4w9WgXcQ'), 'dQw4w9WgXcQ');
      expect(youtubeVideoId('https://youtu.be/dQw4w9WgXcQ?si=abc'), 'dQw4w9WgXcQ');
      expect(youtubeVideoId('https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=30s'), 'dQw4w9WgXcQ');
      expect(youtubeVideoId('https://www.youtube.com/shorts/dQw4w9WgXcQ'), 'dQw4w9WgXcQ');
      expect(youtubeVideoId('https://www.youtube.com/embed/dQw4w9WgXcQ'), 'dQw4w9WgXcQ');
      expect(youtubeVideoId('http://www.youtube.com/watch?v=dQw4w9WgXcQ'), 'dQw4w9WgXcQ');
      expect(youtubeVideoId('https://www.youtube.com/watch?si=abc&v=dQw4w9WgXcQ'), 'dQw4w9WgXcQ');
    });

    test('không nhận link khác hoặc đường dẫn upload', () {
      expect(youtubeVideoId('https://drive.google.com/file/d/abc/view'), isNull);
      expect(youtubeVideoId('/uploads/abc.mp4'), isNull);
      expect(youtubeVideoId(null), isNull);
      expect(youtubeVideoId(''), isNull);
    });
  });

  group('isDirectVideoLink', () {
    test('file upload và link trực tiếp tới file video', () {
      expect(isDirectVideoLink('/uploads/abc.mp4'), isTrue);
      expect(isDirectVideoLink('/media/abc.mp4?exp=1&sig=x'), isTrue);
      expect(isDirectVideoLink('https://cdn.example.com/a/b.MP4?token=1'), isTrue);
      expect(isDirectVideoLink('https://cdn.example.com/a/b.webm'), isTrue);
    });

    test('trang web không phải file video', () {
      expect(isDirectVideoLink('https://www.youtube.com/watch?v=dQw4w9WgXcQ'), isFalse);
      expect(isDirectVideoLink('https://drive.google.com/file/d/abc/view'), isFalse);
      expect(isDirectVideoLink(null), isFalse);
    });
  });

  test('videoLinkProblem chỉ báo lỗi cho link không phát được', () {
    expect(videoLinkProblem('https://youtu.be/dQw4w9WgXcQ'), isNull);
    expect(videoLinkProblem('https://cdn.example.com/a.mp4'), isNull);
    expect(videoLinkProblem('https://drive.google.com/file/d/abc/view'), isNotNull);
  });
}
