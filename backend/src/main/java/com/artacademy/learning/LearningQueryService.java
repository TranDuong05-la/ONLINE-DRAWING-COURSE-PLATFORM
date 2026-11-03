package com.artacademy.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.course.Course;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseModule;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.integration.CourseContentService;
import com.artacademy.course.integration.LessonContent;
import com.artacademy.learning.LearningDtos.CourseDetailDto;
import com.artacademy.learning.LearningDtos.LessonContentDto;
import com.artacademy.learning.LearningDtos.LessonItemDto;
import com.artacademy.learning.LearningDtos.ModuleDto;
import com.artacademy.learning.LearningDtos.MyCourseDto;

/**
 * Phía "đọc" của Khóa học của tôi. Mọi hàm (trừ danh sách khóa của chính mình) đều chứng minh người gọi có quyền
 * học trước. Cấu trúc khóa và nội dung bài (link video, tài liệu) lấy từ phần B.
 */
@Service
@Transactional(readOnly = true)
public class LearningQueryService {
	private final EnrollmentRepository enrollments;
	private final LearnerProfileService profiles;
	private final LessonAccess access;
	private final CourseRepository courses;
	private final CourseLessonRepository lessons;
	private final CourseContentService content;
	private final LessonProgressRepository progress;
	private final AssignmentRepository assignments;
	private final CertificateRepository certificates;
	private final CompletionService completion;

	public LearningQueryService(EnrollmentRepository enrollments, LearnerProfileService profiles, LessonAccess access,
			CourseRepository courses, CourseLessonRepository lessons, CourseContentService content,
			LessonProgressRepository progress, AssignmentRepository assignments, CertificateRepository certificates,
			CompletionService completion) {
		this.enrollments = enrollments;
		this.profiles = profiles;
		this.access = access;
		this.courses = courses;
		this.lessons = lessons;
		this.content = content;
		this.progress = progress;
		this.assignments = assignments;
		this.certificates = certificates;
		this.completion = completion;
	}

	public List<MyCourseDto> myCourses(Integer userId) {
		Integer profileId = profiles.findLearnerProfileId(userId);
		if (profileId == null) {
			return List.of();
		}
		List<Enrollment> mine = enrollments.findByLearnerProfileIdAndStatusOrderByEnrolledAtDescIdDesc(profileId,
				Enrollment.Status.ACTIVE);
		Map<Integer, Course> courseMap = courses.findAllById(mine.stream().map(Enrollment::getCourseId).toList()).stream()
				.collect(Collectors.toMap(Course::getId, Function.identity()));
		Map<Integer, Long> certByCourse = certificates.findByLearnerProfileIdOrderByIssuedAtDesc(profileId).stream()
				.collect(Collectors.toMap(Certificate::getCourseId, Certificate::getId, (a, b) -> a));
		List<MyCourseDto> out = new ArrayList<>();
		for (Enrollment e : mine) {
			Course c = courseMap.get(e.getCourseId());
			if (c == null) {
				continue;
			}
			CompletionService.CourseProgress p = completion.compute(profileId, c.getId());
			out.add(new MyCourseDto(c.getId(), c.getTitle(), c.getThumbnailUrl(), p.totalLessons(),
					p.completedLessons(), p.percent(), p.complete(), p.continueLessonId(), certByCourse.get(c.getId()),
					e.getEnrolledAt()));
		}
		return out;
	}

	public CourseDetailDto courseDetail(Integer userId, Integer courseId) {
		Integer profileId = access.requireCourse(userId, courseId);
		Course c = courses.findById(courseId).orElseThrow(() -> Errors.notFound("Không tìm thấy khóa học"));
		List<CourseLesson> visible = lessons.findVisibleByCourse(courseId);
		Set<Integer> ids = visible.stream().map(CourseLesson::getId).collect(Collectors.toSet());
		Map<Integer, LessonProgress> progressByLesson = progress.findByLearnerProfileIdAndLessonIdIn(profileId, ids)
				.stream().collect(Collectors.toMap(LessonProgress::getLessonId, Function.identity()));
		Map<Integer, Assignment> assignmentByLesson = assignments.findByLessonIdInAndActiveTrue(ids).stream()
				.collect(Collectors.toMap(Assignment::getLessonId, Function.identity()));
		Map<Long, AssignmentSubmission.Status> latest = completion.latestStatus(profileId,
				assignmentByLesson.values().stream().map(Assignment::getId).toList());

		Map<CourseModule, List<LessonItemDto>> modules = new LinkedHashMap<>();
		for (CourseLesson l : visible) {
			LessonProgress lp = progressByLesson.get(l.getId());
			Assignment a = assignmentByLesson.get(l.getId());
			String status = a == null ? null
					: latest.containsKey(a.getId()) ? latest.get(a.getId()).name() : "NOT_SUBMITTED";
			modules.computeIfAbsent(l.getModule(), m -> new ArrayList<>())
					.add(new LessonItemDto(l.getId(), l.getTitle(), l.getDurationSeconds(), l.isRequired(),
							lp != null && Boolean.TRUE.equals(lp.getCompleted()),
							lp == null ? 0 : lp.getLastPositionSeconds(), a != null,
							a != null && Boolean.TRUE.equals(a.getRequired()), status));
		}
		CompletionService.CourseProgress p = completion.compute(profileId, visible);
		Long certId = certificates.findByLearnerProfileIdAndCourseId(profileId, courseId).map(Certificate::getId)
				.orElse(null);
		return new CourseDetailDto(c.getId(), c.getTitle(), c.getThumbnailUrl(), c.getDescription(), p.totalLessons(),
				p.completedLessons(), p.requiredLessons(), p.completedRequiredLessons(), p.requiredAssignments(),
				p.approvedAssignments(), p.percent(), p.complete(), p.continueLessonId(), certId,
				modules.entrySet().stream()
						.map(e -> new ModuleDto(e.getKey().getId(), e.getKey().getTitle(), e.getValue())).toList());
	}

	/** Nội dung bài (có link video/tài liệu) chỉ trả cho người đã có quyền học, kèm vị trí xem tiếp. */
	public LessonContentDto lesson(Integer userId, Integer lessonId) {
		LessonAccess.Granted g = access.requireLesson(userId, lessonId);
		LessonContent lc = content.getLessonContent(lessonId);
		LessonProgress p = progress.findByLearnerProfileIdAndLessonId(g.learnerProfileId(), lessonId).orElse(null);
		List<CourseLesson> all = lessons.findVisibleByCourse(g.courseId());
		Integer prev = null;
		Integer next = null;
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).getId().equals(lessonId)) {
				prev = i > 0 ? all.get(i - 1).getId() : null;
				next = i < all.size() - 1 ? all.get(i + 1).getId() : null;
			}
		}
		boolean hasAssignment = assignments.findByLessonIdAndActiveTrue(lessonId).isPresent();
		return new LessonContentDto(lc.lessonId(), lc.courseId(), lc.title(), lc.description(), lc.videoUrl(),
				lc.durationSeconds(), lc.documentUrl(), lc.required(), p == null ? 0 : p.getLastPositionSeconds(),
				p == null ? 0 : p.getWatchedSeconds(), p != null && Boolean.TRUE.equals(p.getCompleted()),
				hasAssignment, prev, next);
	}
}
