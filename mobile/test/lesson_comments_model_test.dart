import 'package:flutter_test/flutter_test.dart';
import 'package:artnest_app/models/learning_models.dart';

void main() {
  test('comment models preserve BIGINT lesson/course/comment identifiers', () {
    final comments = LessonComments.fromJson({
      'lessonId': 3000000001,
      'lessonTitle': 'Lesson',
      'courseId': 3000000002,
      'courseTitle': 'Course',
      'canModerate': false,
      'total': 2,
      'threads': [
        {
          'comment': {
            'id': 3000000003,
            'parentId': null,
            'authorUserId': 17,
            'authorName': 'Learner',
            'authorRole': 'USER',
            'body': 'Question',
            'mine': true,
          },
          'replies': [
            {
              'id': 3000000004,
              'parentId': 3000000003,
              'authorUserId': 18,
              'authorName': 'Instructor',
              'authorRole': 'INSTRUCTOR',
              'body': 'Answer',
              'mine': false,
            }
          ],
        }
      ],
    });

    expect(comments.lessonId, 3000000001);
    expect(comments.courseId, 3000000002);
    expect(comments.threads.single.comment.id, 3000000003);
    expect(comments.threads.single.replies.single.parentId, 3000000003);
    expect(comments.threads.single.replies.single.roleLabel, 'Giảng viên');
  });
}
