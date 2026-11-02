package com.artacademy.learning;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.learning.certificate.CertificateService;

/**
 * Quy tắc tiến độ và hoàn thành khóa (thống nhất ngày 1):
 * <ul>
 *   <li>Khóa HOÀN THÀNH khi mọi bài học BẮT BUỘC (cờ is_required của B, chỉ tính bài đang hiển thị) đã hoàn thành
 *       VÀ mọi bài tập bắt buộc đang mở đã được duyệt (APPROVED). Nếu khóa không đánh dấu bài bắt buộc nào thì
 *       mọi bài đang hiển thị đều được coi là bắt buộc. Khóa không có bài nào thì không bao giờ hoàn thành.</li>
 *   <li>Một bài học hoàn thành khi đã xem đủ {@code app.learning.complete-ratio} (90%) thời lượng video.</li>
 *   <li>Phần trăm hiển thị = (bài bắt buộc đã xong + bài tập bắt buộc đã nộp, chờ chấm hoặc đạt) / tổng mục bắt buộc.
 *       Chưa hoàn thành thì tối đa 99%.</li>
 * </ul>
 * Mọi con số được TÍNH LẠI từ dữ liệu gốc, không cộng dồn, nên gọi lặp lại không bị tăng trùng.
 */
@Service
public class CompletionService {

	public record CourseProgress(int totalLessons, int completedLessons, int requiredLessons,
			int completedRequiredLessons, int requiredAssignments, int submittedAssignments, int approvedAssignments,
			int basisPoints, int percent, boolean complete, Long continueLessonId) {
	}

	private static final Set<AssignmentSubmission.Status> SUBMITTED = Set.of(AssignmentSubmission.Status.PENDING,
			AssignmentSubmission.Status.APPROVED);

	private final CourseLessonRepository lessons;
	private final LessonProgressRepository progress;
	private final AssignmentRepository assignments;
	private final SubmissionRepository submissions;
	private final EnrollmentRepository enrollments;
	private final CertificateService certificates;

	public CompletionService(CourseLessonRepository lessons, LessonProgressRepository progress,
			AssignmentRepository assignments, SubmissionRepository submissions, EnrollmentRepository enrollments,
			CertificateService certificates) {
		this.lessons = lessons;
		this.progress = progress;
		this.assignments = assignments;
		this.submissions = submissions;
		this.enrollments = enrollments;
		this.certificates = certificates;
	}

	@Transactional(readOnly = true)
	public CourseProgress compute(Integer learnerProfileId, Long courseId) {
		return compute(learnerProfileId, lessons.findVisibleByCourse(courseId));
	}

	/** @param visible bài đang hiển thị của khóa, đúng thứ tự (CourseLessonRepository.findVisibleByCourse). */
	CourseProgress compute(Integer learnerProfileId, List<CourseLesson> visible) {
		if (visible.isEmpty()) {
			return new CourseProgress(0, 0, 0, 0, 0, 0, 0, 0, 0, false, null);
		}
		Set<Long> lessonIds = visible.stream().map(CourseLesson::getId).collect(Collectors.toSet());
		Set<Long> done = progress.findByLearnerProfileIdAndLessonIdIn(learnerProfileId, lessonIds).stream()
				.filter(p -> Boolean.TRUE.equals(p.getCompleted())).map(LessonProgress::getLessonId)
				.collect(Collectors.toSet());

		boolean anyFlagged = visible.stream().anyMatch(CourseLesson::isRequired);
		List<CourseLesson> requiredLessons = anyFlagged ? visible.stream().filter(CourseLesson::isRequired).toList()
				: visible;
		int doneRequired = (int) requiredLessons.stream().filter(l -> done.contains(l.getId())).count();

		Map<Long, Assignment> requiredByLesson = assignments.findByLessonIdInAndActiveTrue(lessonIds).stream()
				.filter(a -> Boolean.TRUE.equals(a.getRequired()))
				.collect(Collectors.toMap(Assignment::getLessonId, Function.identity()));
		Map<Long, AssignmentSubmission.Status> latest = latestStatus(learnerProfileId,
				requiredByLesson.values().stream().map(Assignment::getId).toList());
		int submitted = (int) latest.values().stream().filter(SUBMITTED::contains).count();
		int approved = (int) latest.values().stream().filter(s -> s == AssignmentSubmission.Status.APPROVED).count();

		int totalItems = requiredLessons.size() + requiredByLesson.size();
		int doneItems = doneRequired + submitted;
		boolean complete = doneRequired == requiredLessons.size() && approved == requiredByLesson.size();
		int basisPoints = totalItems == 0 ? 0 : doneItems * 10_000 / totalItems;
		int percent = complete ? 100 : Math.min(basisPoints / 100, 99);

		Long next = null;
		for (CourseLesson l : visible) {
			Assignment a = requiredByLesson.get(l.getId());
			boolean assignmentOk = a == null || latest.get(a.getId()) == AssignmentSubmission.Status.APPROVED;
			if (!done.contains(l.getId()) || !assignmentOk) {
				next = l.getId();
				break;
			}
		}
		return new CourseProgress(visible.size(), done.size(), requiredLessons.size(), doneRequired,
				requiredByLesson.size(), submitted, approved, basisPoints, percent, complete, next);
	}

	/** Trạng thái của lần nộp mới nhất cho từng đề (assignmentId → status). */
	Map<Long, AssignmentSubmission.Status> latestStatus(Integer learnerProfileId, List<Long> assignmentIds) {
		if (assignmentIds.isEmpty()) {
			return Map.of();
		}
		return submissions.findByLearnerProfileIdAndAssignmentIdIn(learnerProfileId, assignmentIds).stream()
				.collect(Collectors.groupingBy(AssignmentSubmission::getAssignmentId,
						Collectors.collectingAndThen(
								Collectors.maxBy(Comparator.comparing(AssignmentSubmission::getAttemptNo)),
								o -> o.orElseThrow().getStatus())));
	}

	/**
	 * Tính lại tiến độ, đánh dấu hoàn thành và cấp chứng nhận đúng một lần. Khóa dòng enrollment (FOR UPDATE)
	 * nên hai request cùng hoàn thành khóa một lúc sẽ chạy lần lượt: request sau thấy chứng nhận đã có.
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public CourseProgress evaluate(Integer learnerProfileId, Long courseId) {
		Enrollment e = enrollments.findForUpdate(learnerProfileId, courseId).orElse(null);
		CourseProgress p = compute(learnerProfileId, courseId);
		if (e == null || !e.isActive()) {
			return p;
		}
		e.setCompletedLessonsCount(p.completedLessons());
		if (p.complete() && !Boolean.TRUE.equals(e.getCompleted())) {
			e.setCompleted(true);
			e.setCompletedAt(LocalDateTime.now());
		}
		enrollments.save(e);
		if (p.complete()) {
			certificates.issueIfMissing(learnerProfileId, courseId);
		}
		return p;
	}
}
