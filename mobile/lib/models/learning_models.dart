// Dữ liệu học tập (phần C) trả về từ backend /api/learning/** và /api/admin/... (bọc trong ApiResponse.data).
// Tên khóa JSON khớp các record trong backend LearningDtos.java.

int _int(dynamic v) => (v as num?)?.toInt() ?? 0;
int? _intOrNull(dynamic v) => (v as num?)?.toInt();
DateTime? _date(dynamic v) => v == null ? null : DateTime.tryParse(v as String);
List<T> _list<T>(dynamic v, T Function(Map<String, dynamic>) f) =>
    (v as List? ?? []).map((e) => f(e as Map<String, dynamic>)).toList();

/// Khóa học viên đã có quyền học, kèm tiến độ.
class MyCourse {
  final int courseId;
  final String title;
  final String? thumbnailUrl;
  final int totalLessons;
  final int completedLessons;
  final int progressPercent;
  final bool completed;
  final int? continueLessonId;
  final int? certificateId;

  MyCourse.fromJson(Map<String, dynamic> j)
      : courseId = _int(j['courseId']),
        title = j['title'] as String? ?? '',
        thumbnailUrl = j['thumbnailUrl'] as String?,
        totalLessons = _int(j['totalLessons']),
        completedLessons = _int(j['completedLessons']),
        progressPercent = _int(j['progressPercent']),
        completed = j['completed'] == true,
        continueLessonId = _intOrNull(j['continueLessonId']),
        certificateId = _intOrNull(j['certificateId']);
}

class LessonItem {
  final int id;
  final String title;
  final int durationSeconds;
  final bool required;
  final bool completed;
  final int lastPositionSeconds;
  final bool hasAssignment;
  final bool assignmentRequired;
  final String? assignmentStatus;

  LessonItem.fromJson(Map<String, dynamic> j)
      : id = _int(j['id']),
        title = j['title'] as String? ?? '',
        durationSeconds = _int(j['durationSeconds']),
        required = j['required'] == true,
        completed = j['completed'] == true,
        lastPositionSeconds = _int(j['lastPositionSeconds']),
        hasAssignment = j['hasAssignment'] == true,
        assignmentRequired = j['assignmentRequired'] == true,
        assignmentStatus = j['assignmentStatus'] as String?;
}

class LearningModule {
  final int id;
  final String title;
  final List<LessonItem> lessons;

  LearningModule.fromJson(Map<String, dynamic> j)
      : id = _int(j['id']),
        title = j['title'] as String? ?? '',
        lessons = _list(j['lessons'], LessonItem.fromJson);
}

class CourseDetail {
  final int courseId;
  final String title;
  final String? thumbnailUrl;
  final String? description;
  final int totalLessons;
  final int completedLessons;
  final int requiredLessons;
  final int completedRequiredLessons;
  final int requiredAssignments;
  final int approvedAssignments;
  final int progressPercent;
  final bool completed;
  final int? continueLessonId;
  final int? certificateId;
  final List<LearningModule> modules;

  CourseDetail.fromJson(Map<String, dynamic> j)
      : courseId = _int(j['courseId']),
        title = j['title'] as String? ?? '',
        thumbnailUrl = j['thumbnailUrl'] as String?,
        description = j['description'] as String?,
        totalLessons = _int(j['totalLessons']),
        completedLessons = _int(j['completedLessons']),
        requiredLessons = _int(j['requiredLessons']),
        completedRequiredLessons = _int(j['completedRequiredLessons']),
        requiredAssignments = _int(j['requiredAssignments']),
        approvedAssignments = _int(j['approvedAssignments']),
        progressPercent = _int(j['progressPercent']),
        completed = j['completed'] == true,
        continueLessonId = _intOrNull(j['continueLessonId']),
        certificateId = _intOrNull(j['certificateId']),
        modules = _list(j['modules'], LearningModule.fromJson);
}

class LessonContent {
  final int id;
  final int courseId;
  final String title;
  final String? description;
  final String? videoUrl;
  final int durationSeconds;
  final String? materialUrl;
  final bool required;
  final int lastPositionSeconds;
  final int watchedSeconds;
  final bool completed;
  final bool hasAssignment;
  final int? previousLessonId;
  final int? nextLessonId;

  LessonContent.fromJson(Map<String, dynamic> j)
      : id = _int(j['id']),
        courseId = _int(j['courseId']),
        title = j['title'] as String? ?? '',
        description = j['description'] as String?,
        videoUrl = j['videoUrl'] as String?,
        durationSeconds = _int(j['durationSeconds']),
        materialUrl = j['materialUrl'] as String?,
        required = j['required'] == true,
        lastPositionSeconds = _int(j['lastPositionSeconds']),
        watchedSeconds = _int(j['watchedSeconds']),
        completed = j['completed'] == true,
        hasAssignment = j['hasAssignment'] == true,
        previousLessonId = _intOrNull(j['previousLessonId']),
        nextLessonId = _intOrNull(j['nextLessonId']);
}

class ProgressResult {
  final bool completed;
  final int courseProgressPercent;
  final bool courseCompleted;

  ProgressResult.fromJson(Map<String, dynamic> j)
      : completed = j['completed'] == true,
        courseProgressPercent = _int(j['courseProgressPercent']),
        courseCompleted = j['courseCompleted'] == true;
}

class Submission {
  final int id;
  final int attemptNo;
  final String status;
  final String? note;
  final String? feedback;
  final double? score;
  final DateTime? submittedAt;
  final DateTime? reviewedAt;

  /// Đường dẫn ảnh riêng tư trên server, ví dụ /api/learning/submissions/5/image (cần header đăng nhập).
  final String imagePath;

  Submission.fromJson(Map<String, dynamic> j)
      : id = _int(j['id']),
        attemptNo = _int(j['attemptNo']),
        status = j['status'] as String? ?? '',
        note = j['note'] as String?,
        feedback = j['feedback'] as String?,
        score = (j['score'] as num?)?.toDouble(),
        submittedAt = _date(j['submittedAt']),
        reviewedAt = _date(j['reviewedAt']),
        imagePath = j['imagePath'] as String? ?? '';
}

class AssignmentInfo {
  final int id;
  final String title;
  final String lessonTitle;
  final String? instructions;
  final bool required;

  /// Server quyết định: true khi chưa nộp lần nào hoặc lần gần nhất bị yêu cầu sửa.
  final bool canSubmit;
  final String? latestStatus;
  final List<Submission> submissions;

  AssignmentInfo.fromJson(Map<String, dynamic> j)
      : id = _int(j['id']),
        title = j['title'] as String? ?? '',
        lessonTitle = j['lessonTitle'] as String? ?? '',
        instructions = j['instructions'] as String?,
        required = j['required'] == true,
        canSubmit = j['canSubmit'] == true,
        latestStatus = j['latestStatus'] as String?,
        submissions = _list(j['submissions'], Submission.fromJson);
}

// ---------------------------------------------------------------- admin

class AdminSubmission {
  final int id;
  final String? assignmentTitle;
  final String? assignmentInstructions;
  final int lessonId;
  final String? lessonTitle;
  final int courseId;
  final String? courseTitle;
  final String? learnerName;
  final String? learnerEmail;
  final int attemptNo;
  final String status;
  final String? note;
  final double? score;
  final String? feedback;
  final DateTime? submittedAt;
  final DateTime? reviewedAt;
  final String imagePath;
  final List<Submission> history;

  AdminSubmission.fromJson(Map<String, dynamic> j)
      : id = _int(j['id']),
        assignmentTitle = j['assignmentTitle'] as String?,
        assignmentInstructions = j['assignmentInstructions'] as String?,
        lessonId = _int(j['lessonId']),
        lessonTitle = j['lessonTitle'] as String?,
        courseId = _int(j['courseId']),
        courseTitle = j['courseTitle'] as String?,
        learnerName = j['learnerName'] as String?,
        learnerEmail = j['learnerEmail'] as String?,
        attemptNo = _int(j['attemptNo']),
        status = j['status'] as String? ?? '',
        note = j['note'] as String?,
        score = (j['score'] as num?)?.toDouble(),
        feedback = j['feedback'] as String?,
        submittedAt = _date(j['submittedAt']),
        reviewedAt = _date(j['reviewedAt']),
        imagePath = j['imagePath'] as String? ?? '',
        history = _list(j['history'], Submission.fromJson);
}

class SubmissionPage {
  final List<AdminSubmission> items;
  final int total;
  final int page;
  final int size;

  SubmissionPage.fromJson(Map<String, dynamic> j)
      : items = _list(j['items'], AdminSubmission.fromJson),
        total = _int(j['total']),
        page = _int(j['page']),
        size = _int(j['size']);
}

class SubmissionSummary {
  final int pending;
  final int needsRevision;
  final int approved;

  SubmissionSummary.fromJson(Map<String, dynamic> j)
      : pending = _int(j['pending']),
        needsRevision = _int(j['needsRevision']),
        approved = _int(j['approved']);
}

class CourseOption {
  final int id;
  final String title;

  CourseOption(this.id, this.title);

  CourseOption.fromJson(Map<String, dynamic> j)
      : id = _int(j['id']),
        title = j['title'] as String? ?? '';
}

/// Một bài học trong màn soạn đề của admin, kèm đề bài (nếu có).
class LessonAssignmentAdmin {
  final int lessonId;
  final String lessonTitle;
  final String moduleTitle;
  final bool lessonHidden;
  final int? assignmentId;
  final String? title;
  final String? instructions;
  final bool required;
  final bool active;

  LessonAssignmentAdmin.fromJson(Map<String, dynamic> j)
      : lessonId = _int(j['lessonId']),
        lessonTitle = j['lessonTitle'] as String? ?? '',
        moduleTitle = j['moduleTitle'] as String? ?? '',
        lessonHidden = j['lessonHidden'] == true,
        assignmentId = _intOrNull(j['assignmentId']),
        title = j['title'] as String?,
        instructions = j['instructions'] as String?,
        required = j['required'] == true,
        active = j['active'] == true;
}

// ---------------------------------------------------------------- helpers

String assignmentStatusLabel(String? status) => switch (status) {
      'PENDING' => 'Đã nộp • chờ chấm',
      'APPROVED' => 'Đạt',
      'NEEDS_REVISION' => 'Cần sửa và nộp lại',
      'NOT_SUBMITTED' || null => 'Chưa nộp',
      _ => status,
    };

String formatSeconds(int? s) {
  if (s == null || s <= 0) return '';
  return '${s ~/ 60}:${(s % 60).toString().padLeft(2, '0')}';
}

// ---------------------------------------------------------------- thông báo, bài tập của tôi, chứng nhận (admin)

class NotificationItem {
  final int id;
  final String type;
  final String title;
  final String? body;
  final DateTime? createdAt;
  final bool read;
  final int? courseId;
  final int? lessonId;
  final int? targetId;

  NotificationItem.fromJson(Map<String, dynamic> j)
      : id = _int(j['id']),
        type = j['type'] as String? ?? '',
        title = j['title'] as String? ?? '',
        body = j['body'] as String?,
        createdAt = _date(j['createdAt']),
        read = j['read'] == true,
        courseId = _intOrNull(j['courseId']),
        lessonId = _intOrNull(j['lessonId']),
        targetId = _intOrNull(j['targetId']);
}

/// Một dòng trong "Bài tập của tôi": đề + trạng thái lần nộp mới nhất.
class MyAssignment {
  final int assignmentId;
  final int lessonId;
  final String lessonTitle;
  final int courseId;
  final String courseTitle;
  final String title;
  final bool required;

  /// NOT_SUBMITTED, NEEDS_REVISION, PENDING hoặc APPROVED
  final String status;
  final double? score;
  final String? feedback;
  final DateTime? submittedAt;

  MyAssignment.fromJson(Map<String, dynamic> j)
      : assignmentId = _int(j['assignmentId']),
        lessonId = _int(j['lessonId']),
        lessonTitle = j['lessonTitle'] as String? ?? '',
        courseId = _int(j['courseId']),
        courseTitle = j['courseTitle'] as String? ?? '',
        title = j['title'] as String? ?? '',
        required = j['required'] == true,
        status = j['status'] as String? ?? 'NOT_SUBMITTED',
        score = (j['score'] as num?)?.toDouble(),
        feedback = j['feedback'] as String?,
        submittedAt = _date(j['submittedAt']);
}

/// Admin: chứng nhận đã cấp cho học viên.
class AdminCertificate {
  final int id;
  final String code;
  final int courseId;
  final String courseTitle;
  final String learnerName;
  final String? learnerEmail;
  final DateTime? issuedAt;
  final String emailStatus;

  AdminCertificate.fromJson(Map<String, dynamic> j)
      : id = _int(j['id']),
        code = j['code'] as String? ?? '',
        courseId = _int(j['courseId']),
        courseTitle = j['courseTitle'] as String? ?? '',
        learnerName = j['learnerName'] as String? ?? '',
        learnerEmail = j['learnerEmail'] as String?,
        issuedAt = _date(j['issuedAt']),
        emailStatus = j['emailStatus'] as String? ?? 'PENDING';
}
