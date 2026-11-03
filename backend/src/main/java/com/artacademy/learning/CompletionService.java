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
 * Quy táº¯c tiáº¿n Ä‘á»™ vÃ  hoÃ n thÃ nh khÃ³a (thá»‘ng nháº¥t ngÃ y 1):
 * <ul>
 *   <li>KhÃ³a HOÃ€N THÃ€NH khi má»i bÃ i há»c Báº®T BUá»˜C (cá» is_required cá»§a B, chá»‰ tÃ­nh bÃ i Ä‘ang hiá»ƒn thá»‹) Ä‘Ã£ hoÃ n thÃ nh
 *       VÃ€ má»i bÃ i táº­p báº¯t buá»™c Ä‘ang má»Ÿ Ä‘Ã£ Ä‘Æ°á»£c duyá»‡t (APPROVED). Náº¿u khÃ³a khÃ´ng Ä‘Ã¡nh dáº¥u bÃ i báº¯t buá»™c nÃ o thÃ¬
 *       má»i bÃ i Ä‘ang hiá»ƒn thá»‹ Ä‘á»u Ä‘Æ°á»£c coi lÃ  báº¯t buá»™c. KhÃ³a khÃ´ng cÃ³ bÃ i nÃ o thÃ¬ khÃ´ng bao giá» hoÃ n thÃ nh.</li>
 *   <li>Má»™t bÃ i há»c hoÃ n thÃ nh khi Ä‘Ã£ xem Ä‘á»§ {@code app.learning.complete-ratio} (90%) thá»i lÆ°á»£ng video.</li>
 *   <li>Pháº§n trÄƒm hiá»ƒn thá»‹ = (bÃ i báº¯t buá»™c Ä‘Ã£ xong + bÃ i táº­p báº¯t buá»™c Ä‘Ã£ ná»™p, chá» cháº¥m hoáº·c Ä‘áº¡t) / tá»•ng má»¥c báº¯t buá»™c.
 *       ChÆ°a hoÃ n thÃ nh thÃ¬ tá»‘i Ä‘a 99%.</li>
 * </ul>
 * Má»i con sá»‘ Ä‘Æ°á»£c TÃNH Láº I tá»« dá»¯ liá»‡u gá»‘c, khÃ´ng cá»™ng dá»“n, nÃªn gá»i láº·p láº¡i khÃ´ng bá»‹ tÄƒng trÃ¹ng.
 */
@Service
public class CompletionService {

	public record CourseProgress(int totalLessons, int completedLessons, int requiredLessons,
			int completedRequiredLessons, int requiredAssignments, int submittedAssignments, int approvedAssignments,
			int basisPoints, int percent, boolean complete, Integer continueLessonId) {
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
	public CourseProgress compute(Integer learnerProfileId, Integer courseId) {
		return compute(learnerProfileId, lessons.findVisibleByCourse(courseId));
	}

	/** @param visible bÃ i Ä‘ang hiá»ƒn thá»‹ cá»§a khÃ³a, Ä‘Ãºng thá»© tá»± (CourseLessonRepository.findVisibleByCourse). */
	CourseProgress compute(Integer learnerProfileId, List<CourseLesson> visible) {
		if (visible.isEmpty()) {
			return new CourseProgress(0, 0, 0, 0, 0, 0, 0, 0, 0, false, null);
		}
		Set<Integer> lessonIds = visible.stream().map(CourseLesson::getId).collect(Collectors.toSet());
		Set<Integer> done = progress.findByLearnerProfileIdAndLessonIdIn(learnerProfileId, lessonIds).stream()
				.filter(p -> Boolean.TRUE.equals(p.getCompleted())).map(LessonProgress::getLessonId)
				.collect(Collectors.toSet());

		boolean anyFlagged = visible.stream().anyMatch(CourseLesson::isRequired);
		List<CourseLesson> requiredLessons = anyFlagged ? visible.stream().filter(CourseLesson::isRequired).toList()
				: visible;
		int doneRequired = (int) requiredLessons.stream().filter(l -> done.contains(l.getId())).count();

		Map<Integer, Assignment> requiredByLesson = assignments.findByLessonIdInAndActiveTrue(lessonIds).stream()
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

		Integer next = null;
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

	/** Tráº¡ng thÃ¡i cá»§a láº§n ná»™p má»›i nháº¥t cho tá»«ng Ä‘á» (assignmentId â†’ status). */
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
	 * TÃ­nh láº¡i tiáº¿n Ä‘á»™, Ä‘Ã¡nh dáº¥u hoÃ n thÃ nh vÃ  cáº¥p chá»©ng nháº­n Ä‘Ãºng má»™t láº§n. KhÃ³a dÃ²ng enrollment (FOR UPDATE)
	 * nÃªn hai request cÃ¹ng hoÃ n thÃ nh khÃ³a má»™t lÃºc sáº½ cháº¡y láº§n lÆ°á»£t: request sau tháº¥y chá»©ng nháº­n Ä‘Ã£ cÃ³.
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public CourseProgress evaluate(Integer learnerProfileId, Integer courseId) {
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
