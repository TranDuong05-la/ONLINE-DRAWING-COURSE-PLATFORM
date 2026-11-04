package com.artacademy.learning.comment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.security.AuthenticatedUserPrincipal;
import com.artacademy.support.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
		"app.payment.vnpay.enabled=false",
		"app.payment.vnpay.session-api.enabled=false",
		"app.payment.vnpay.ipn.enabled=false",
		"app.payment.vnpay.query-dr.enabled=false"
})
class LessonCommentIntegrationTest extends IntegrationTestBase {
	@Autowired private UserRepository users;
	@Autowired private LessonCommentRepository comments;
	private Long fixtureLessonId;
	private Integer fixtureUserId;

	@AfterEach
	void deleteOnlyCommentsCreatedByThisTest() {
		if (fixtureLessonId != null) {
			comments.findByLessonIdOrderByIdDesc(fixtureLessonId).forEach(c -> comments.deleteById(c.getId()));
			fixtureLessonId = null;
		}
		if (fixtureUserId != null) { users.deleteById(fixtureUserId); fixtureUserId = null; }
	}

	@Test
	void supportCanCreateAndReadLongIdCommentThread() throws Exception {
		User admin = newAdmin();
		fixtureUserId = admin.getId();
		var course = newCourse("Comment course", 100_000, null, CourseLevel.BASIC, CourseStatus.PUBLISHED);
		var lesson = newLesson(newModule(course, "Module", 1, false), "Lesson", 1, VIDEO, false, false);
		fixtureLessonId = lesson.getId();
		var principal = user(principal(admin));
		var first = mvc.perform(post("/api/learning/lessons/{id}/comments", lesson.getId()).with(principal)
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Question\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.authorRole").value("ADMIN")).andReturn();
		long commentId = json.readTree(first.getResponse().getContentAsString()).at("/data/id").asLong();
		mvc.perform(post("/api/learning/lessons/{id}/comments", lesson.getId()).with(principal)
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Reply\",\"parentId\":" + commentId + "}"))
				.andExpect(status().isOk());
		mvc.perform(get("/api/learning/lessons/{id}/comments", lesson.getId()).with(principal))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.lessonId").value(lesson.getId()))
				.andExpect(jsonPath("$.data.courseId").value(course.getId())).andExpect(jsonPath("$.data.total").value(2))
				.andExpect(jsonPath("$.data.threads[0].replies.length()").value(1));
		mvc.perform(get("/api/learning/comments/inbox").with(principal))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
	}

	@Test
	void commentsRequireAuthenticationAndBlankBodiesAreRejected() throws Exception {
		var course = newCourse("Comments auth", 100_000, null, CourseLevel.BASIC, CourseStatus.PUBLISHED);
		var lesson = newLesson(newModule(course, "Module", 1, false), "Lesson", 1, VIDEO, false, false);
		fixtureLessonId = lesson.getId();
		mvc.perform(get("/api/learning/lessons/{id}/comments", lesson.getId())).andExpect(status().isUnauthorized());
		User admin = newAdmin();
		fixtureUserId = admin.getId();
		mvc.perform(post("/api/learning/lessons/{id}/comments", lesson.getId()).with(user(principal(admin)))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"   \"}"))
				.andExpect(status().isBadRequest());
	}

	private User newAdmin() {
		return users.save(User.builder().fullName("Admin").email("comment-admin-" + UUID.randomUUID() + "@test.local")
				.passwordHash("unused").role(Role.ADMIN).isActive(true).build());
	}
	private static AuthenticatedUserPrincipal principal(User user) {
		return new AuthenticatedUserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(), Role.ADMIN, true, 1);
	}
}
