package com.artacademy.learning;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.artacademy.course.Course;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseRepository;
import com.artacademy.entity.UserProfile;
import com.artacademy.learning.LearningDtos.AdminLessonAssignmentDto;
import com.artacademy.learning.LearningDtos.AdminSubmissionDto;
import com.artacademy.learning.LearningDtos.AssignmentDto;
import com.artacademy.learning.LearningDtos.AssignmentRequest;
import com.artacademy.learning.LearningDtos.CourseOption;
import com.artacademy.learning.LearningDtos.MyAssignmentDto;
import com.artacademy.learning.LearningDtos.PageDto;
import com.artacademy.learning.LearningDtos.ReviewRequest;
import com.artacademy.learning.LearningDtos.SubmissionDto;
import com.artacademy.learning.LearningDtos.SubmissionSummary;
import com.artacademy.learning.notification.LearningNotification;
import com.artacademy.learning.notification.NotificationRequest;
import com.artacademy.learning.storage.FileStorage;
import com.artacademy.learning.storage.ImageValidator;
import com.artacademy.repository.UserProfileRepository;

/**
 * Đề bài, bài nộp, phản hồi và duyệt.
 *
 * <p>Học viên chỉ thấy/nộp bài của khóa mình có quyền học, và chỉ xem được bài nộp của chính mình (của người khác
 * trả 404). Mỗi lần nộp là MỘT DÒNG MỚI (attempt_no tăng dần), lịch sử không bị ghi đè.
 */
@Service
public class AssignmentService {
	private static final int MAX_NOTE = 2000;

	private final AssignmentRepository assignments;
	private final SubmissionRepository submissions;
	private final CourseLessonRepository lessons;
	private final CourseRepository courses;
	private final UserProfileRepository userProfiles;
	private final EnrollmentRepository enrollments;
	private final LearnerProfileService profiles;
	private final LessonAccess access;
	private final CompletionService completion;
	private final ImageValidator imageValidator;
	private final FileStorage storage;
	private final ApplicationEventPublisher events;

	public AssignmentService(AssignmentRepository assignments, SubmissionRepository submissions,
			CourseLessonRepository lessons, CourseRepository courses, UserProfileRepository userProfiles,
			EnrollmentRepository enrollments, LearnerProfileService profiles, LessonAccess access,
			CompletionService completion, ImageValidator imageValidator, FileStorage storage,
			ApplicationEventPublisher events) {
		this.assignments = assignments;
		this.submissions = submissions;
		this.lessons = lessons;
		this.courses = courses;
		this.userProfiles = userProfiles;
		this.enrollments = enrollments;
		this.profiles = profiles;
		this.access = access;
		this.completion = completion;
		this.imageValidator = imageValidator;
		this.storage = storage;
		this.events = events;
	}

	// ---------------------------------------------------------------- học viên

	@Transactional(readOnly = true)
	public AssignmentDto get(Integer userId, Long lessonId) {
		LessonAccess.Granted g = access.requireLesson(userId, lessonId);
		Assignment a = activeAssignment(lessonId);
		List<AssignmentSubmission> history = submissions
				.findByAssignmentIdAndLearnerProfileIdOrderByAttemptNoDesc(a.getId(), g.learnerProfileId());
		AssignmentSubmission.Status latest = history.isEmpty() ? null : history.get(0).getStatus();
		boolean canSubmit = latest == null || latest == AssignmentSubmission.Status.NEEDS_REVISION;
		return new AssignmentDto(a.getId(), lessonId, g.lessonTitle(), g.courseId(), a.getTitle(), a.getInstructions(),
				Boolean.TRUE.equals(a.getRequired()), canSubmit, latest == null ? null : latest.name(),
				history.stream().map(this::toDto).toList());
	}

	/**
	 * Nộp bài: (1) có quyền học, (2) ảnh JPEG/PNG thật và không quá 5MB, (3) lần nộp gần nhất phải là
	 * NEEDS_REVISION (hoặc chưa nộp lần nào), (4) lưu file riêng tư rồi ghi DB. Hai lần nộp đồng thời không thể
	 * cùng thành công vì attempt_no là unique theo đề + học viên.
	 */
	@Transactional
	public SubmissionDto submit(Integer userId, Long lessonId, MultipartFile image, String note) {
		LessonAccess.Granted g = access.requireLesson(userId, lessonId);
		Assignment a = activeAssignment(lessonId);
		if (note != null && note.length() > MAX_NOTE) {
			throw Errors.badRequest("Ghi chú tối đa " + MAX_NOTE + " ký tự");
		}
		var valid = imageValidator.validate(image);
		var latest = submissions.findFirstByAssignmentIdAndLearnerProfileIdOrderByAttemptNoDesc(a.getId(),
				g.learnerProfileId());
		if (latest.isPresent() && latest.get().getStatus() != AssignmentSubmission.Status.NEEDS_REVISION) {
			throw Errors.conflict(latest.get().getStatus() == AssignmentSubmission.Status.APPROVED
					? "Bài tập đã được duyệt"
					: "Bài nộp trước đang chờ duyệt");
		}
		String key = storage.save("submissions", valid.bytes(), valid.extension());
		AssignmentSubmission s = new AssignmentSubmission();
		s.setAssignmentId(a.getId());
		s.setLessonId(lessonId);
		s.setCourseId(g.courseId());
		s.setLearnerProfileId(g.learnerProfileId());
		s.setImageKey(key);
		s.setNote(note == null || note.isBlank() ? null : note.trim());
		s.setAttemptNo(latest.map(x -> x.getAttemptNo() + 1).orElse(1));
		try {
			return toDto(submissions.saveAndFlush(s));
		} catch (DataIntegrityViolationException e) {
			storage.deleteQuietly(key);
			throw Errors.conflict("Bài nộp đang được xử lý, vui lòng tải lại");
		}
	}

	/** Ảnh bài nộp: chỉ chủ bài hoặc admin. Người khác nhận 404 để không lộ bài nộp có tồn tại. */
	@Transactional(readOnly = true)
	public byte[] image(Integer userId, boolean admin, Long submissionId) {
		AssignmentSubmission s = submissions.findById(submissionId)
				.orElseThrow(() -> Errors.notFound("Không tìm thấy bài nộp"));
		if (!admin && !s.getLearnerProfileId().equals(profiles.findLearnerProfileId(userId))) {
			throw Errors.notFound("Không tìm thấy bài nộp");
		}
		return storage.read(s.getImageKey());
	}

	/** Tab "Bài tập": mọi đề của các khóa đang có quyền học, việc cần làm xếp trước. */
	@Transactional(readOnly = true)
	public List<MyAssignmentDto> myAssignments(Integer userId) {
		Integer profileId = profiles.findLearnerProfileId(userId);
		if (profileId == null) {
			return List.of();
		}
		List<MyAssignmentDto> out = new ArrayList<>();
		for (Enrollment e : enrollments.findByLearnerProfileIdAndStatusOrderByEnrolledAtDescIdDesc(profileId,
				Enrollment.Status.ACTIVE)) {
			Course c = courses.findById(e.getCourseId()).orElse(null);
			if (c == null) {
				continue;
			}
			Map<Long, CourseLesson> lessonMap = lessons.findVisibleByCourse(c.getId()).stream()
					.collect(Collectors.toMap(CourseLesson::getId, Function.identity()));
			List<Assignment> list = assignments.findByLessonIdInAndActiveTrue(lessonMap.keySet());
			Map<Long, AssignmentSubmission> latest = latestSubmissions(profileId,
					list.stream().map(Assignment::getId).toList());
			for (Assignment a : list) {
				CourseLesson l = lessonMap.get(a.getLessonId());
				AssignmentSubmission s = latest.get(a.getId());
				out.add(new MyAssignmentDto(a.getId(), l.getId(), l.getTitle(), c.getId(), c.getTitle(), a.getTitle(),
						Boolean.TRUE.equals(a.getRequired()), s == null ? "NOT_SUBMITTED" : s.getStatus().name(),
						s == null ? null : s.getScore(), s == null ? null : s.getFeedback(),
						s == null ? null : s.getSubmittedAt()));
			}
		}
		List<String> order = List.of("NEEDS_REVISION", "NOT_SUBMITTED", "PENDING", "APPROVED");
		out.sort(Comparator.comparingInt((MyAssignmentDto d) -> order.indexOf(d.status()))
				.thenComparing(MyAssignmentDto::courseId));
		return out;
	}

	// ---------------------------------------------------------------- admin: đề bài

	@Transactional(readOnly = true)
	public List<AdminLessonAssignmentDto> lessonsWithAssignments(Long courseId) {
		if (!courses.existsById(courseId)) {
			throw Errors.notFound("Không tìm thấy khóa học");
		}
		List<CourseLesson> all = lessons.findAllByCourse(courseId);
		Map<Long, Assignment> byLesson = assignments
				.findByLessonIdIn(all.stream().map(CourseLesson::getId).toList()).stream()
				.collect(Collectors.toMap(Assignment::getLessonId, Function.identity()));
		return all.stream().map(l -> toAdminLessonDto(l, byLesson.get(l.getId()))).toList();
	}

	/** Tạo hoặc sửa đề của một bài học (mỗi bài tối đa một đề). Ẩn đề thay vì xóa để giữ lịch sử bài nộp. */
	@Transactional
	public AdminLessonAssignmentDto upsert(Long lessonId, AssignmentRequest req) {
		CourseLesson l = lessons.findById(lessonId).orElseThrow(() -> Errors.notFound("Không tìm thấy bài học"));
		Assignment a = assignments.findByLessonId(lessonId).orElseGet(() -> {
			Assignment n = new Assignment();
			n.setLessonId(lessonId);
			return n;
		});
		boolean wasVisible = a.getId() != null && Boolean.TRUE.equals(a.getActive());
		a.setTitle(req.title().trim());
		a.setInstructions(req.instructions() == null || req.instructions().isBlank() ? null : req.instructions().trim());
		a.setRequired(req.required());
		a.setActive(req.active());
		a = assignments.saveAndFlush(a);
		// Đề mới (hoặc vừa bật hiển thị) trên bài đang hiển thị: báo cho mọi học viên đang học khóa đó.
		if (!wasVisible && Boolean.TRUE.equals(a.getActive()) && l.isVisible()) {
			var course = l.getModule().getCourse();
			events.publishEvent(NotificationRequest.toCourseLearners(course.getId(),
					LearningNotification.Type.NEW_ASSIGNMENT, "ASSIGN:" + a.getId(), "Bài tập mới được giao",
					a.getTitle() + " • " + l.getTitle() + " (" + course.getTitle() + ")", l.getId(), a.getId()));
		}
		return toAdminLessonDto(l, a);
	}

	// ---------------------------------------------------------------- admin: chấm bài

	@Transactional(readOnly = true)
	public SubmissionSummary summary() {
		return new SubmissionSummary(submissions.countByStatus(AssignmentSubmission.Status.PENDING),
				submissions.countByStatus(AssignmentSubmission.Status.NEEDS_REVISION),
				submissions.countByStatus(AssignmentSubmission.Status.APPROVED));
	}

	@Transactional(readOnly = true)
	public PageDto<AdminSubmissionDto> list(Long courseId, String status, int page, int size) {
		AssignmentSubmission.Status st = parseStatus(status);
		Page<AssignmentSubmission> result = submissions.search(courseId, st,
				PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
		return new PageDto<>(result.getContent().stream().map(s -> toAdminDto(s, false)).toList(),
				result.getTotalElements(), result.getNumber(), result.getSize());
	}

	/** Khóa đã có bài nộp, cho bộ lọc ở màn chấm bài. */
	@Transactional(readOnly = true)
	public List<CourseOption> coursesWithSubmissions() {
		return courses.findAllById(submissions.distinctCourseIds()).stream()
				.map(c -> new CourseOption(c.getId(), c.getTitle())).toList();
	}

	@Transactional(readOnly = true)
	public AdminSubmissionDto detail(Long submissionId) {
		return toAdminDto(submissions.findById(submissionId)
				.orElseThrow(() -> Errors.notFound("Không tìm thấy bài nộp")), true);
	}

	/**
	 * Duyệt / yêu cầu sửa. Khóa dòng bài nộp nên hai admin chấm cùng lúc sẽ chạy lần lượt: người sau thấy bài không
	 * còn PENDING và nhận 409 thay vì ghi đè. Duyệt đạt thì xét lại hoàn thành khóa (có thể cấp chứng nhận, đúng
	 * một lần). Yêu cầu sửa bắt buộc có phản hồi để học viên biết cần sửa gì.
	 */
	@Transactional
	public AdminSubmissionDto review(Integer reviewerUserId, Long submissionId, ReviewRequest req) {
		AssignmentSubmission.Status decision = parseStatus(req.decision().trim().toUpperCase());
		if (decision != AssignmentSubmission.Status.APPROVED && decision != AssignmentSubmission.Status.NEEDS_REVISION) {
			throw Errors.badRequest("decision phải là APPROVED hoặc NEEDS_REVISION");
		}
		String feedback = req.feedback() == null || req.feedback().isBlank() ? null : req.feedback().trim();
		if (decision == AssignmentSubmission.Status.NEEDS_REVISION && feedback == null) {
			throw Errors.badRequest("Vui lòng nhập phản hồi khi yêu cầu sửa bài");
		}
		AssignmentSubmission s = submissions.findForUpdate(submissionId)
				.orElseThrow(() -> Errors.notFound("Không tìm thấy bài nộp"));
		if (s.getStatus() != AssignmentSubmission.Status.PENDING) {
			throw Errors.conflict("Bài nộp này đã được xử lý");
		}
		s.setStatus(decision);
		s.setFeedback(feedback);
		s.setScore(req.score());
		s.setReviewedByUserId(reviewerUserId);
		s.setReviewedAt(LocalDateTime.now());
		submissions.saveAndFlush(s);
		if (decision == AssignmentSubmission.Status.APPROVED) {
			completion.evaluate(s.getLearnerProfileId(), s.getCourseId());
		}
		boolean approved = decision == AssignmentSubmission.Status.APPROVED;
		String title = assignments.findById(s.getAssignmentId()).map(Assignment::getTitle).orElse("Bài tập");
		StringBuilder body = new StringBuilder(title);
		if (s.getScore() != null) {
			body.append("\nĐiểm: ").append(s.getScore().stripTrailingZeros().toPlainString());
		}
		if (feedback != null) {
			body.append("\nNhận xét: ").append(feedback);
		}
		events.publishEvent(NotificationRequest.toUser(profiles.userIdOf(s.getLearnerProfileId()),
				approved ? LearningNotification.Type.GRADED_APPROVED : LearningNotification.Type.GRADED_REVISION,
				"GRADED:" + s.getId(), approved ? "Bài tập đã được duyệt đạt" : "Bài tập cần sửa và nộp lại",
				body.toString(), s.getCourseId(), s.getLessonId(), s.getId()));
		return toAdminDto(s, true);
	}

	// ---------------------------------------------------------------- mapping

	private Assignment activeAssignment(Long lessonId) {
		return assignments.findByLessonIdAndActiveTrue(lessonId)
				.orElseThrow(() -> Errors.notFound("Bài học này không có bài tập"));
	}

	private Map<Long, AssignmentSubmission> latestSubmissions(Integer profileId, List<Long> assignmentIds) {
		if (assignmentIds.isEmpty()) {
			return Map.of();
		}
		return submissions.findByLearnerProfileIdAndAssignmentIdIn(profileId, assignmentIds).stream()
				.collect(Collectors.toMap(AssignmentSubmission::getAssignmentId, Function.identity(),
						(x, y) -> x.getAttemptNo() >= y.getAttemptNo() ? x : y));
	}

	private static AssignmentSubmission.Status parseStatus(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		try {
			return AssignmentSubmission.Status.valueOf(status.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw Errors.badRequest("Trạng thái không hợp lệ");
		}
	}

	private SubmissionDto toDto(AssignmentSubmission s) {
		return new SubmissionDto(s.getId(), s.getAssignmentId(), s.getLessonId(), s.getAttemptNo(), s.getStatus().name(),
				s.getNote(), s.getScore(), s.getFeedback(), s.getSubmittedAt(), s.getReviewedAt(),
				"/api/learning/submissions/" + s.getId() + "/image");
	}

	private AdminSubmissionDto toAdminDto(AssignmentSubmission s, boolean withHistory) {
		Assignment a = assignments.findById(s.getAssignmentId()).orElse(null);
		CourseLesson lesson = lessons.findById(s.getLessonId()).orElse(null);
		Course course = courses.findById(s.getCourseId()).orElse(null);
		UserProfile learner = userProfiles.findById(s.getLearnerProfileId()).orElse(null);
		List<SubmissionDto> history = withHistory
				? submissions.findByAssignmentIdAndLearnerProfileIdOrderByAttemptNoDesc(s.getAssignmentId(),
						s.getLearnerProfileId()).stream().map(this::toDto).toList()
				: List.of();
		return new AdminSubmissionDto(s.getId(), s.getAssignmentId(), a == null ? null : a.getTitle(),
				a == null ? null : a.getInstructions(), s.getLessonId(), lesson == null ? null : lesson.getTitle(),
				s.getCourseId(), course == null ? null : course.getTitle(), s.getLearnerProfileId(),
				learner == null ? null : learner.getDisplayName(),
				learner == null ? null : learner.getUser().getEmail(), s.getAttemptNo(), s.getStatus().name(),
				s.getNote(), s.getScore(), s.getFeedback(), s.getSubmittedAt(), s.getReviewedAt(),
				"/api/admin/submissions/" + s.getId() + "/image", history);
	}

	private static AdminLessonAssignmentDto toAdminLessonDto(CourseLesson l, Assignment a) {
		return new AdminLessonAssignmentDto(l.getId(), l.getTitle(), l.getModule().getTitle(), !l.isVisible(),
				a == null ? null : a.getId(), a == null ? null : a.getTitle(), a == null ? null : a.getInstructions(),
				a != null && Boolean.TRUE.equals(a.getRequired()), a != null && Boolean.TRUE.equals(a.getActive()));
	}
}
