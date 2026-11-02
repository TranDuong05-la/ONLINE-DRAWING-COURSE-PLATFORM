package com.artacademy.learning;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseStatus;

/**
 * Kiểm tra quyền vào một bài học trước khi trả nội dung / lưu tiến độ / nộp bài:
 * bài phải tồn tại và đang hiển thị, khóa không phải bản nháp, và người dùng có quyền học ACTIVE với khóa đó.
 * Bài ẩn hoặc không tồn tại đều trả 404 như nhau.
 */
@Service
public class LessonAccess {

	/** Thông tin đã kiểm tra quyền (dữ liệu thuần, dùng được ngoài transaction). */
	public record Granted(Integer userId, Integer learnerProfileId, Long lessonId, Long courseId, String lessonTitle,
			int durationSeconds) {
	}

	private final CourseLessonRepository lessons;
	private final EnrollmentService enrollments;

	public LessonAccess(CourseLessonRepository lessons, EnrollmentService enrollments) {
		this.lessons = lessons;
		this.enrollments = enrollments;
	}

	@Transactional(readOnly = true)
	public Granted requireLesson(Integer userId, Long lessonId) {
		CourseLesson l = lessons.findById(lessonId).orElseThrow(() -> Errors.notFound("Không tìm thấy bài học"));
		if (!l.isVisible() || l.getModule().getCourse().getStatus() == CourseStatus.DRAFT) {
			throw Errors.notFound("Không tìm thấy bài học");
		}
		Long courseId = l.getModule().getCourse().getId();
		Enrollment e = enrollments.requireActiveEnrollment(userId, courseId);
		return new Granted(userId, e.getLearnerProfileId(), l.getId(), courseId, l.getTitle(), l.getDurationSeconds());
	}

	/** Khóa phải tồn tại (không phải nháp) và người dùng có quyền học. Trả về learnerProfileId. */
	@Transactional(readOnly = true)
	public Integer requireCourse(Integer userId, Long courseId) {
		return enrollments.requireActiveEnrollment(userId, courseId).getLearnerProfileId();
	}
}
