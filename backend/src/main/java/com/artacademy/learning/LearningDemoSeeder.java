package com.artacademy.learning;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.course.Course;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseRepository;
import com.artacademy.repository.UserRepository;

/**
 * Dữ liệu mẫu của phần C (chỉ khi dev: cùng điều kiện với dữ liệu mẫu của B, tức profile h2).
 * Chạy SAU khi B đã nạp khóa học mẫu (ApplicationReadyEvent). Chỉ thêm, không ghi đè, chạy lại không bị trùng:
 * <ul>
 *   <li>Đề bài tập bắt buộc cho bài "Kỹ thuật loang màu" và "Vẽ mắt và mũi".</li>
 *   <li>Cấp quyền học (MANUAL, không qua thanh toán) 2 khóa mẫu cho tài khoản học viên mẫu.</li>
 * </ul>
 * Khóa "Vẽ phong cảnh bằng chì màu" (nháp) không được cấp, dùng để thử "chưa mua không học được".
 */
@Component
@ConditionalOnProperty(name = { "app.seed.enabled", "app.seed.users.enabled" }, havingValue = "true")
public class LearningDemoSeeder {
	private static final Logger log = LoggerFactory.getLogger(LearningDemoSeeder.class);

	private final CourseRepository courses;
	private final CourseLessonRepository lessons;
	private final AssignmentRepository assignments;
	private final UserRepository users;
	private final EnrollmentService enrollments;
	private final String learnerEmail;

	public LearningDemoSeeder(CourseRepository courses, CourseLessonRepository lessons,
			AssignmentRepository assignments, UserRepository users, EnrollmentService enrollments,
			@Value("${app.seed.learner.email:}") String learnerEmail) {
		this.courses = courses;
		this.lessons = lessons;
		this.assignments = assignments;
		this.users = users;
		this.enrollments = enrollments;
		this.learnerEmail = learnerEmail;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void seed() {
		List<Integer> demoCourses = new ArrayList<>();
		for (Course c : courses.findAll()) {
			switch (c.getCode()) {
			case "KH-WATERCOLOR" -> {
				assignment(c, "Kỹ thuật loang màu", "Bài tập: Loang màu bầu trời",
						"Vẽ một bầu trời hoàng hôn bằng kỹ thuật loang ướt trên ướt (khổ A5 trở lên). "
								+ "Chụp ảnh rõ, đủ sáng toàn bộ bức tranh rồi nộp (JPEG/PNG, tối đa 5MB).");
				demoCourses.add(c.getId());
			}
			case "KH-PORTRAIT" -> {
				assignment(c, "Vẽ mắt và mũi", "Bài tập: Phác thảo mắt và mũi",
						"Phác thảo một cặp mắt và mũi theo tỉ lệ đã học, có đổ bóng cơ bản. "
								+ "Ghi chú loại bút chì đã dùng.");
				demoCourses.add(c.getId());
			}
			default -> {
			}
			}
		}
		if (learnerEmail.isBlank() || demoCourses.isEmpty()) {
			return;
		}
		users.findByEmail(learnerEmail.trim().toLowerCase()).ifPresent(u -> {
			enrollments.grantManually(u.getId(), demoCourses);
			log.info("Demo: granted courses {} to {}", demoCourses, learnerEmail);
		});
	}

	private void assignment(Course course, String lessonTitle, String title, String instructions) {
		for (CourseLesson l : lessons.findAllByCourse(course.getId())) {
			if (l.getTitle().equals(lessonTitle) && assignments.findByLessonId(l.getId()).isEmpty()) {
				Assignment a = new Assignment();
				a.setLessonId(l.getId());
				a.setTitle(title);
				a.setInstructions(instructions);
				a.setRequired(true);
				a.setActive(true);
				assignments.save(a);
			}
		}
	}
}
