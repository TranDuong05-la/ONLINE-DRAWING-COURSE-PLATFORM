package com.artacademy.instructor;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.artacademy.course.Course;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseModule;
import com.artacademy.course.CourseModuleRepository;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.Assignment;
import com.artacademy.learning.AssignmentRepository;
import com.artacademy.repository.UserRepository;
import com.artacademy.security.AuthenticatedUserPrincipal;

/**
 * Phân quyền giảng viên, chạy cả ứng dụng với H2 và Spring Security thật: giảng viên chỉ thấy / sửa / chấm bài
 * khóa được phân công; khóa khác trả 404; USER và STAFF không vào được /api/instructor; giảng viên không gọi được API
 * admin (giá, xuất bản); gỡ phân công hoặc đổi role là mất quyền ngay.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:artnest_instructor_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"app.seed.enabled=false",
		"app.storage.root=target/test-instructor-storage",
		"app.upload.dir=target/test-instructor-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstructorAccessIntegrationTest {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired CourseRepository courses;
	@Autowired CourseModuleRepository modules;
	@Autowired CourseLessonRepository lessons;
	@Autowired AssignmentRepository assignments;
	@Autowired CourseInstructorRepository courseInstructors;

	User admin;
	User instructor;
	User otherInstructor;
	User learner;
	User staff;
	Course mine;
	Course notMine;
	CourseModule mineModule;
	CourseLesson mineLesson;
	CourseLesson notMineLesson;

	@BeforeEach
	void setUp() throws Exception {
		admin = newUser(Role.ADMIN);
		instructor = newUser(Role.INSTRUCTOR);
		otherInstructor = newUser(Role.INSTRUCTOR);
		learner = newUser(Role.USER);
		staff = newUser(Role.STAFF);
		mine = newCourse("Khóa được phân công");
		notMine = newCourse("Khóa của người khác");
		mineModule = modules.save(new CourseModule(mine, "Chương 1", 1));
		mineLesson = lesson(mineModule, "Bài 1");
		notMineLesson = lesson(modules.save(new CourseModule(notMine, "Chương 1", 1)), "Bài 1");
		assignmentOn(mineLesson);
		assignmentOn(notMineLesson);
		assign(mine, instructor).andExpect(status().isCreated());
		assign(notMine, otherInstructor).andExpect(status().isCreated());
	}

	// ------------------------------------------------------------------ ai vào được

	@Test
	void onlyInstructorAndAdminCanEnterInstructorArea() throws Exception {
		mvc.perform(get("/api/instructor/courses").with(as(learner))).andExpect(status().isForbidden());
		mvc.perform(get("/api/instructor/courses").with(as(staff))).andExpect(status().isForbidden());
		mvc.perform(get("/api/instructor/courses")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/instructor/courses").with(as(instructor))).andExpect(status().isOk());
		mvc.perform(get("/api/instructor/courses").with(as(admin))).andExpect(status().isOk());
	}

	@Test
	void instructorSeesOnlyAssignedCourses() throws Exception {
		mvc.perform(get("/api/instructor/courses").with(as(instructor)))
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].id").value(mine.getId()));
		mvc.perform(get("/api/instructor/courses/" + mine.getId()).with(as(instructor))).andExpect(status().isOk());
		mvc.perform(get("/api/instructor/courses/" + notMine.getId()).with(as(instructor)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/instructor/courses/" + notMine.getId() + "/enrollments").with(as(instructor)))
				.andExpect(status().isNotFound());
	}

	@Test
	void instructorCannotUseAdminApis() throws Exception {
		mvc.perform(put("/api/admin/courses/" + mine.getId() + "/status").with(as(instructor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/submissions").with(as(instructor))).andExpect(status().isForbidden());
		mvc.perform(post("/api/admin/learning/courses/" + mine.getId() + "/enrollments").with(as(instructor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + learner.getEmail() + "\"}"))
				.andExpect(status().isForbidden());
	}

	// ------------------------------------------------------------------ nội dung

	@Test
	void instructorEditsContentOnlyOfAssignedCourse() throws Exception {
		mvc.perform(post("/api/instructor/modules/" + mineModule.getId() + "/lessons").with(as(instructor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Bài mới\",\"videoUrl\":\"https://example.com/new.mp4\",\"durationSeconds\":10}"))
				.andExpect(status().isCreated());
		mvc.perform(put("/api/instructor/lessons/" + mineLesson.getId() + "/assignment").with(as(instructor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Đề đã sửa\",\"required\":true,\"active\":true}"))
				.andExpect(status().isOk());

		mvc.perform(put("/api/instructor/lessons/" + notMineLesson.getId()).with(as(instructor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Sửa trộm\",\"videoUrl\":\"https://example.com/v.mp4\"}"))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/instructor/courses/" + notMine.getId() + "/modules").with(as(instructor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Chương lạ\",\"hidden\":true}"))
				.andExpect(status().isNotFound());
		mvc.perform(put("/api/instructor/lessons/" + notMineLesson.getId() + "/assignment").with(as(instructor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Đề lạ\",\"required\":true,\"active\":true}"))
				.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ chấm bài

	@Test
	void instructorGradesOnlySubmissionsOfAssignedCourse() throws Exception {
		long mineSubmission = submit(mine, mineLesson);
		long otherSubmission = submit(notMine, notMineLesson);

		mvc.perform(get("/api/instructor/submissions").with(as(instructor)))
				.andExpect(jsonPath("$.data.total").value(1))
				.andExpect(jsonPath("$.data.items[0].id").value(mineSubmission));
		mvc.perform(get("/api/instructor/submissions/summary").with(as(instructor)))
				.andExpect(jsonPath("$.data.pending").value(1));
		mvc.perform(get("/api/instructor/submissions/" + otherSubmission).with(as(instructor)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/instructor/submissions/" + otherSubmission + "/image").with(as(instructor)))
				.andExpect(status().isNotFound());
		mvc.perform(review(otherSubmission).with(as(instructor))).andExpect(status().isNotFound());

		mvc.perform(get("/api/instructor/submissions/" + mineSubmission + "/image").with(as(instructor)))
				.andExpect(status().isOk());
		mvc.perform(review(mineSubmission).with(as(instructor))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("NEEDS_REVISION"));
		// ADMIN thấy cả hai
		mvc.perform(get("/api/instructor/submissions").with(as(admin))).andExpect(jsonPath("$.data.total").value(2));
	}

	// ------------------------------------------------------------------ phân công

	@Test
	void adminAssignRules() throws Exception {
		assign(mine, learner).andExpect(status().isBadRequest());
		assign(mine, instructor).andExpect(status().isConflict());
		mvc.perform(post("/api/admin/courses/" + mine.getId() + "/instructors").with(as(instructor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + otherInstructor.getEmail() + "\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/courses/" + mine.getId() + "/instructors").with(as(admin)))
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].email").value(instructor.getEmail()));
	}

	@Test
	void unassignOrRoleChangeRemovesAccessImmediately() throws Exception {
		mvc.perform(get("/api/instructor/courses/" + mine.getId()).with(as(instructor))).andExpect(status().isOk());
		mvc.perform(delete("/api/admin/courses/" + mine.getId() + "/instructors/" + instructor.getId()).with(as(admin)))
				.andExpect(status().isOk());
		mvc.perform(get("/api/instructor/courses/" + mine.getId()).with(as(instructor)))
				.andExpect(status().isNotFound());

		// Còn phân công nhưng bị đổi role về USER: token cũ vẫn ghi INSTRUCTOR, nhưng role đọc lại từ DB
		otherInstructor.setRole(Role.USER);
		users.save(otherInstructor);
		mvc.perform(get("/api/instructor/courses/" + notMine.getId())
				.with(user(new AuthenticatedUserPrincipal(otherInstructor.getId(), otherInstructor.getEmail(), "x",
						Role.INSTRUCTOR, true))))
				.andExpect(status().isForbidden());
	}

	// ------------------------------------------------------------------ helpers

	private org.springframework.test.web.servlet.ResultActions assign(Course c, User u) throws Exception {
		return mvc.perform(post("/api/admin/courses/" + c.getId() + "/instructors").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + u.getEmail() + "\"}"));
	}

	/** Admin cấp quyền học cho học viên, học viên nộp ảnh; trả id bài nộp. */
	private long submit(Course c, CourseLesson l) throws Exception {
		mvc.perform(post("/api/admin/learning/courses/" + c.getId() + "/enrollments").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + learner.getEmail() + "\"}"))
				.andExpect(status().isOk());
		BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(img, "png", png);
		String body = mvc.perform(multipart("/api/learning/lessons/" + l.getId() + "/submissions")
				.file(new MockMultipartFile("image", "bai.png", "image/png", png.toByteArray()))
				.param("note", "Bài của em").with(as(learner)))
				.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
		Matcher m = Pattern.compile("\"data\":\\{\"id\":(\\d+)").matcher(body);
		if (!m.find()) {
			throw new AssertionError("Không đọc được id bài nộp: " + body);
		}
		return Long.parseLong(m.group(1));
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder review(long id) {
		return post("/api/instructor/submissions/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
				.content("{\"decision\":\"NEEDS_REVISION\",\"feedback\":\"Tô bóng thêm\",\"score\":6}");
	}

	private RequestPostProcessor as(User u) {
		return user(new AuthenticatedUserPrincipal(u.getId(), u.getEmail(), "x", u.getRole(), true));
	}

	private User newUser(Role role) {
		return users.save(User.builder().fullName("Người dùng " + role).email(UUID.randomUUID() + "@test.vn")
				.passwordHash("x").role(role).isActive(true).build());
	}

	private Course newCourse(String title) {
		Course c = new Course();
		c.setCode("I-" + UUID.randomUUID().toString().substring(0, 8));
		c.setSlug("i-" + UUID.randomUUID());
		c.setTitle(title);
		c.setShortDescription("Mô tả ngắn");
		c.setPrice(100_000);
		c.setLevel(CourseLevel.BASIC);
		c.setStatus(CourseStatus.PUBLISHED);
		return courses.save(c);
	}

	private CourseLesson lesson(CourseModule m, String title) {
		CourseLesson l = new CourseLesson(m, title, 1);
		l.setVideoUrl("https://example.com/v.mp4");
		l.setDurationSeconds(4);
		l.setRequired(true);
		return lessons.save(l);
	}

	private void assignmentOn(CourseLesson l) {
		Assignment a = new Assignment();
		a.setLessonId(l.getId());
		a.setTitle("Vẽ một quả táo");
		a.setRequired(true);
		a.setActive(true);
		assignments.save(a);
	}
}
