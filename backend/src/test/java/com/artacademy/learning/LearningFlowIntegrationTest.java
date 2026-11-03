package com.artacademy.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.artacademy.course.Course;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseModule;
import com.artacademy.course.CourseModuleRepository;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.learning.certificate.CertificateMailer;
import com.artacademy.learning.claim.CourseClaim;
import com.artacademy.learning.claim.CourseClaimConflictException;
import com.artacademy.learning.claim.CourseClaimRepository;
import com.artacademy.learning.claim.CourseClaimService;
import com.artacademy.repository.UserRepository;
import com.artacademy.security.AuthenticatedUserPrincipal;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts;

/**
 * Các tình huống bắt buộc của phần C, chạy cả ứng dụng với H2 và Spring Security thật:
 * chưa mua không học được; không xem bài nộp của người khác; bài cần sửa nộp lại được; thiếu bài bắt buộc chưa
 * hoàn thành; duyệt / gọi lại không tạo chứng nhận trùng; email lỗi vẫn tải được chứng nhận; cấp quyền và thu hồi
 * quyền theo yêu cầu của D (giữ chỗ → xác nhận / nhả chỗ / hoàn tiền).
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:artnest_learning_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"app.seed.enabled=false",
		"app.storage.root=target/test-learning-storage",
		"app.upload.dir=target/test-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LearningFlowIntegrationTest {

	/** Thay dịch vụ email của A: đếm số lần gửi, có thể giả lập lỗi. */
	static class FakeMailer implements CertificateMailer {
		final AtomicInteger sent = new AtomicInteger();
		final AtomicBoolean fail = new AtomicBoolean();

		@Override
		public void send(String to, String fullName, String courseTitle, String code, String url, String date) {
			if (fail.get()) {
				throw new IllegalStateException("SMTP down");
			}
			sent.incrementAndGet();
		}
	}

	@TestConfiguration
	static class MailConfig {
		@Bean
		@Primary
		FakeMailer fakeMailer() {
			return new FakeMailer();
		}
	}

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired CourseRepository courses;
	@Autowired CourseModuleRepository modules;
	@Autowired CourseLessonRepository lessons;
	@Autowired AssignmentRepository assignments;
	@Autowired CertificateRepository certificates;
	@Autowired EnrollmentRepository enrollments;
	@Autowired CourseClaimRepository claims;
	@Autowired CourseClaimService claimService;
	@Autowired CompletionService completion;
	@Autowired LearnerProfileService profiles;
	@Autowired PlatformTransactionManager txManager;
	@Autowired FakeMailer mailer;

	User learner;
	User other;
	User admin;
	Course course;
	CourseLesson lesson1;
	CourseLesson lesson2;
	CourseLesson optionalLesson;
	Assignment assignment;

	@BeforeEach
	void setUp() {
		mailer.fail.set(false);
		learner = newUser(Role.USER);
		other = newUser(Role.USER);
		admin = newUser(Role.ADMIN);
		course = new Course();
		course.setCode("C-" + UUID.randomUUID().toString().substring(0, 8));
		course.setSlug("c-" + UUID.randomUUID());
		course.setTitle("Khóa thử phần C");
		course.setPrice(100_000);
		course.setLevel(CourseLevel.BASIC);
		course.setStatus(CourseStatus.PUBLISHED);
		course = courses.save(course);
		CourseModule m = modules.save(new CourseModule(course, "Chương 1", 1));
		lesson1 = lesson(m, "Bài 1", 1, true);
		lesson2 = lesson(m, "Bài 2 (có bài tập)", 2, true);
		optionalLesson = lesson(m, "Bài đọc thêm", 3, false);
		assignment = new Assignment();
		assignment.setLessonId(lesson2.getId());
		assignment.setTitle("Vẽ một quả táo");
		assignment.setRequired(true);
		assignment.setActive(true);
		assignment = assignments.save(assignment);
	}

	// ------------------------------------------------------------------ quyền học

	@Test
	void notPurchasedCannotLearn() throws Exception {
		mvc.perform(get("/api/learning/courses/" + course.getId()).with(as(learner))).andExpect(status().isForbidden());
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId()).with(as(learner))).andExpect(status().isForbidden());
		mvc.perform(put("/api/learning/lessons/" + lesson1.getId() + "/progress").with(as(learner))
				.contentType(MediaType.APPLICATION_JSON).content("{\"positionSeconds\":5,\"watchedDeltaSeconds\":5}"))
				.andExpect(status().isForbidden());
		mvc.perform(submission(lesson2, learner)).andExpect(status().isForbidden());
		mvc.perform(get("/api/learning/courses").with(as(learner))).andExpect(jsonPath("$.data", hasSize(0)));
		// chưa đăng nhập
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId())).andExpect(status().isUnauthorized());
	}

	@Test
	void paymentConfirmedByDGrantsAccessAndVideoUrl() throws Exception {
		buyViaD(learner, 501);
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId()).with(as(learner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.videoUrl").value("https://example.com/v.mp4"));
		mvc.perform(get("/api/learning/courses").with(as(learner))).andExpect(jsonPath("$.data", hasSize(1)));
		// người khác vẫn không học được
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId()).with(as(other))).andExpect(status().isForbidden());
	}

	@Test
	void claimLifecycleFollowsAgreementWithD() {
		Instant now = Instant.now();
		Instant expires = now.plus(30, ChronoUnit.MINUTES);
		// giữ chỗ cho Order 601
		tx(() -> claimService.claimForOrder(learner.getId(), course.getId().intValue(), 601, now, expires));
		assertThat(claimService.hasActivePendingPurchase(learner.getId(), course.getId().intValue(), now)).isTrue();
		// Order khác không giữ được cùng khóa
		assertThatThrownBy(() -> tx(() -> claimService.claimForOrder(learner.getId(), course.getId().intValue(), 602,
				now, expires))).isInstanceOf(CourseClaimConflictException.class);
		// gọi lại cho cùng Order: không lỗi, không tạo trùng
		tx(() -> claimService.claimForOrder(learner.getId(), course.getId().intValue(), 601, now, expires));
		// Order hết hạn → nhả chỗ, Order mới giữ được
		tx(() -> claimService.releaseCourseForExpiredOrder(learner.getId(), course.getId().intValue(), 601, expires));
		assertThat(claimService.isOwnedBy(learner.getId(), course.getId().intValue())).isFalse();
		tx(() -> claimService.claimForOrder(learner.getId(), course.getId().intValue(), 603, now, expires));
		// thanh toán thành công → có quyền học; xác nhận lại không lỗi
		tx(() -> claimService.confirmCourseForOrder(learner.getId(), course.getId().intValue(), 603, now));
		tx(() -> claimService.confirmCourseForOrder(learner.getId(), course.getId().intValue(), 603, now));
		assertThat(claimService.isOwnedBy(learner.getId(), course.getId().intValue())).isTrue();
		// đã thanh toán thì không được release
		assertThatThrownBy(() -> tx(() -> claimService.releaseForOrder(learner.getId(), course.getId().intValue(), 603,
				"CANCELLED", now))).isInstanceOf(IllegalStateException.class);
		// hàm ghi bắt buộc chạy trong transaction của D
		assertThatThrownBy(() -> claimService.claimForOrder(other.getId(), course.getId().intValue(), 604, now, expires))
				.hasMessageContaining("transaction");
	}

	@Test
	void refundOfComboRevokesOnlyEnrollmentsGrantedByThatOrderItem() throws Exception {
		Course second = courses.save(copyOf("Khóa thứ hai"));
		modules.save(new CourseModule(second, "C1", 1));
		// học viên đã có khóa "second" từ trước (cấp tay); Combo mới chỉ cấp khóa "course"
		tx(() -> profiles.learnerProfileId(learner.getId()));
		new TransactionTemplate(txManager).executeWithoutResult(s ->
				learnerEnroll(second.getId()));
		Instant now = Instant.now();
		var lines = List.of(new CourseClaimContracts.ClaimLine(9001, course.getId().longValue(),
				CourseClaimContracts.ClaimSourceType.COMBO_COMPONENT, 0));
		tx(() -> claimService.reserve(new CourseClaimContracts.ReservationCommand(learner.getId(), 701, lines, now,
				now.plus(30, ChronoUnit.MINUTES))));
		tx(() -> claimService.confirm(new CourseClaimContracts.ConfirmationCommand(learner.getId(), 701, lines, now)));
		assertThat(claimService.isOwnedBy(learner.getId(), course.getId().intValue())).isTrue();

		tx(() -> claimService.refundOrderItem(new CourseClaimContracts.RefundOrderItemCommand(learner.getId(), 701, 9001,
				"refund-op-1", now)));
		tx(() -> claimService.refundOrderItem(new CourseClaimContracts.RefundOrderItemCommand(learner.getId(), 701, 9001,
				"refund-op-1", now))); // gọi lại: idempotent
		assertThat(claimService.isOwnedBy(learner.getId(), course.getId().intValue())).isFalse();
		assertThat(claimService.isOwnedBy(learner.getId(), second.getId().intValue())).isTrue();
		assertThat(claims.findByOrderItemIdOrderByCourseIdAsc(9001)).allMatch(c -> c.getStatus() == CourseClaim.Status.REFUNDED);
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId()).with(as(learner))).andExpect(status().isForbidden());
	}

	// ------------------------------------------------------------------ bài nộp

	@Test
	void cannotSeeOrReviewOthersSubmissionsAsLearner() throws Exception {
		buyViaD(learner, 801);
		buyViaD(other, 802);
		long id = submitAndGetId(learner);
		mvc.perform(get("/api/learning/submissions/" + id + "/image").with(as(learner))).andExpect(status().isOk());
		mvc.perform(get("/api/learning/submissions/" + id + "/image").with(as(other))).andExpect(status().isNotFound());
		mvc.perform(get("/api/admin/submissions/" + id).with(as(learner))).andExpect(status().isForbidden());
		mvc.perform(review(id, "APPROVED", null).with(as(learner))).andExpect(status().isForbidden());
		mvc.perform(get("/api/learning/lessons/" + lesson2.getId() + "/assignment").with(as(other)))
				.andExpect(jsonPath("$.data.submissions", hasSize(0)));
		mvc.perform(get("/api/admin/submissions/" + id + "/image").with(as(admin))).andExpect(status().isOk());
	}

	@Test
	void needsRevisionCanBeResubmittedButPendingCannot() throws Exception {
		buyViaD(learner, 901);
		long first = submitAndGetId(learner);
		mvc.perform(submission(lesson2, learner)).andExpect(status().isConflict()); // đang chờ duyệt
		mvc.perform(review(first, "NEEDS_REVISION", null).with(as(admin))).andExpect(status().isBadRequest()); // thiếu phản hồi
		mvc.perform(review(first, "NEEDS_REVISION", "Thêm bóng đổ").with(as(admin))).andExpect(status().isOk());
		mvc.perform(get("/api/learning/lessons/" + lesson2.getId() + "/assignment").with(as(learner)))
				.andExpect(jsonPath("$.data.canSubmit").value(true))
				.andExpect(jsonPath("$.data.latestStatus").value("NEEDS_REVISION"));
		mvc.perform(submission(lesson2, learner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.attemptNo").value(2));
		mvc.perform(get("/api/learning/lessons/" + lesson2.getId() + "/assignment").with(as(learner)))
				.andExpect(jsonPath("$.data.submissions", hasSize(2)))
				.andExpect(jsonPath("$.data.submissions[1].feedback").value("Thêm bóng đổ"));
	}

	@Test
	void rejectsNonImageUpload() throws Exception {
		buyViaD(learner, 951);
		MockMultipartFile fake = new MockMultipartFile("image", "a.png", "image/png", "not an image".getBytes());
		mvc.perform(multipart("/api/learning/lessons/" + lesson2.getId() + "/submissions").file(fake).with(as(learner)))
				.andExpect(status().isBadRequest());
	}

	// ------------------------------------------------------------------ hoàn thành + chứng nhận

	@Test
	void missingRequiredItemsKeepCourseIncompleteThenApprovalIssuesOneCertificate() throws Exception {
		buyViaD(learner, 1001);
		watchFully(lesson1);
		watchFully(lesson2);
		// đủ bài học bắt buộc (bài đọc thêm không bắt buộc) nhưng bài tập bắt buộc chưa được duyệt
		mvc.perform(get("/api/learning/courses/" + course.getId()).with(as(learner)))
				.andExpect(jsonPath("$.data.completed").value(false))
				.andExpect(jsonPath("$.data.certificateId").isEmpty());
		long id = submitAndGetId(learner);
		mvc.perform(get("/api/learning/courses/" + course.getId()).with(as(learner)))
				.andExpect(jsonPath("$.data.completed").value(false))
				.andExpect(jsonPath("$.data.progressPercent").value(99));
		mvc.perform(review(id, "APPROVED", "Tốt").with(as(admin))).andExpect(status().isOk());
		mvc.perform(get("/api/learning/courses/" + course.getId()).with(as(learner)))
				.andExpect(jsonPath("$.data.completed").value(true))
				.andExpect(jsonPath("$.data.progressPercent").value(100));
		// duyệt lại (gọi lại) → 409, không cấp thêm
		mvc.perform(review(id, "APPROVED", "Tốt").with(as(admin))).andExpect(status().isConflict());
		Integer profileId = profiles.findLearnerProfileId(learner.getId());
		new TransactionTemplate(txManager).executeWithoutResult(s -> completion.evaluate(profileId, course.getId()));
		new TransactionTemplate(txManager).executeWithoutResult(s -> completion.evaluate(profileId, course.getId()));
		assertThat(certificates.findByLearnerProfileIdOrderByIssuedAtDesc(profileId)).hasSize(1);
		awaitEmailStatus(profileId, Certificate.EmailStatus.SENT);
	}

	@Test
	void emailFailureStillLetsLearnerDownloadCertificate() throws Exception {
		mailer.fail.set(true);
		buyViaD(learner, 1101);
		watchFully(lesson1);
		watchFully(lesson2);
		long id = submitAndGetId(learner);
		mvc.perform(review(id, "APPROVED", null).with(as(admin))).andExpect(status().isOk());
		Integer profileId = profiles.findLearnerProfileId(learner.getId());
		Certificate c = awaitEmailStatus(profileId, Certificate.EmailStatus.FAILED);
		mvc.perform(get("/api/learning/certificates").with(as(learner)))
				.andExpect(jsonPath("$.data[0].emailStatus").value("FAILED"))
				.andExpect(jsonPath("$.data[0].code").value(c.getCertificateCode()));
		mvc.perform(get("/api/learning/certificates/" + c.getId() + "/pdf").with(as(learner)))
				.andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
		mvc.perform(get("/api/learning/certificates/" + c.getId() + "/pdf").with(as(other)))
				.andExpect(status().isNotFound());
		// link ký số ngắn hạn mở được không cần đăng nhập; token sai thì bị chặn
		String body = mvc.perform(get("/api/learning/certificates/" + c.getId() + "/download-link").with(as(learner)))
				.andReturn().getResponse().getContentAsString();
		String token = body.replaceAll(".*token=([^\"]+)\".*", "$1");
		mvc.perform(get("/api/files/certificates/" + c.getId() + "/pdf").param("token", token)).andExpect(status().isOk());
		mvc.perform(get("/api/files/certificates/" + c.getId() + "/pdf").param("token", "bad")).andExpect(status().isForbidden());
	}

	// ------------------------------------------------------------------ thông báo + chứng nhận cho admin

	@Autowired com.artacademy.learning.notification.LearningNotificationRepository notifications;

	@Test
	void learnerIsNotifiedOfEnrollmentAssignmentVideoGradingAndCertificate() throws Exception {
		buyViaD(learner, 1201);
		awaitNotification(learner, "COURSE_ENROLLED");

		// admin giao đề mới ở bài 1 → mọi học viên của khóa nhận thông báo, người không học khóa thì không
		mvc.perform(put("/api/admin/lessons/" + lesson1.getId() + "/assignment").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Đề mới\",\"instructions\":\"Vẽ cái ly\",\"required\":false,\"active\":true}"))
				.andExpect(status().isOk());
		awaitNotification(learner, "NEW_ASSIGNMENT");

		// B thêm video cho bài học (lưu entity của B) → học viên nhận thông báo video mới
		CourseLesson l = lessons.findById(optionalLesson.getId()).orElseThrow();
		l.setVideoUrl("https://example.com/new-video.mp4");
		lessons.save(l);
		awaitNotification(learner, "NEW_LESSON_VIDEO");

		// chấm bài + chứng nhận
		watchFully(lesson1);
		watchFully(lesson2);
		long id = submitAndGetId(learner);
		mvc.perform(review(id, "APPROVED", "Tốt").with(as(admin))).andExpect(status().isOk());
		awaitNotification(learner, "GRADED_APPROVED");
		awaitNotification(learner, "CERTIFICATE");

		assertThat(notifications.findByUserIdOrderByCreatedAtDescIdDesc(other.getId(),
				org.springframework.data.domain.PageRequest.of(0, 50))).isEmpty();

		// chuông: đếm chưa đọc, đánh dấu đã đọc
		mvc.perform(get("/api/learning/notifications/unread-count").with(as(learner)))
				.andExpect(jsonPath("$.data.unread").value(5));
		mvc.perform(post("/api/learning/notifications/read-all").with(as(learner))).andExpect(status().isOk());
		mvc.perform(get("/api/learning/notifications/unread-count").with(as(learner)))
				.andExpect(jsonPath("$.data.unread").value(0));
		mvc.perform(get("/api/learning/notifications").with(as(learner)))
				.andExpect(jsonPath("$.data", hasSize(5)))
				.andExpect(jsonPath("$.data[0].read").value(true));

		// admin xem chứng nhận đã cấp, có tên học viên; học viên không gọi được API admin
		mvc.perform(get("/api/admin/certificates").param("courseId", course.getId().toString()).with(as(admin)))
				.andExpect(jsonPath("$.data.items", hasSize(1)))
				.andExpect(jsonPath("$.data.items[0].learnerName").value(learner.getFullName()))
				.andExpect(jsonPath("$.data.items[0].learnerEmail").value(learner.getEmail()));
		mvc.perform(get("/api/admin/certificates").with(as(learner))).andExpect(status().isForbidden());
	}

	private void awaitNotification(User u, String type) throws Exception {
		for (int i = 0; i < 100; i++) {
			boolean found = notifications
					.findByUserIdOrderByCreatedAtDescIdDesc(u.getId(), org.springframework.data.domain.PageRequest.of(0, 50))
					.stream().anyMatch(n -> n.getType().name().equals(type));
			if (found) {
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("notification " + type + " was not created");
	}

	// ------------------------------------------------------------------ admin cấp / thu hồi quyền học

	@Test
	void adminGrantsAndRevokesManualEnrollment() throws Exception {
		String grant = "{\"email\":\"" + learner.getEmail() + "\"}";
		mvc.perform(post("/api/admin/learning/courses/" + course.getId() + "/enrollments").with(as(learner))
				.contentType(MediaType.APPLICATION_JSON).content(grant)).andExpect(status().isForbidden());
		String body = mvc.perform(post("/api/admin/learning/courses/" + course.getId() + "/enrollments").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content(grant))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("MANUAL"))
				.andExpect(jsonPath("$.data.status").value("ACTIVE"))
				.andReturn().getResponse().getContentAsString();
		long enrollmentId = Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId()).with(as(learner))).andExpect(status().isOk());
		awaitNotification(learner, "COURSE_ENROLLED");
		// cấp trùng / email không tồn tại
		mvc.perform(post("/api/admin/learning/courses/" + course.getId() + "/enrollments").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content(grant)).andExpect(status().isConflict());
		mvc.perform(post("/api/admin/learning/courses/" + course.getId() + "/enrollments").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"khong-co@test.vn\"}"))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/admin/learning/courses/" + course.getId() + "/enrollments").with(as(admin)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data[0].learnerEmail").value(learner.getEmail()));
		// thu hồi quyền cấp tay
		mvc.perform(post("/api/admin/learning/enrollments/" + enrollmentId + "/revoke").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"cấp nhầm\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REVOKED"));
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId()).with(as(learner))).andExpect(status().isForbidden());
	}

	@Test
	void adminCannotRevokePaidEnrollmentOrGrantWhilePaymentPending() throws Exception {
		buyViaD(learner, 811);
		Enrollment paid = enrollments.findByCourseIdAndStatus(course.getId(), Enrollment.Status.ACTIVE).stream()
				.filter(e -> e.getUserId().equals(learner.getId())).findFirst().orElseThrow();
		mvc.perform(post("/api/admin/learning/enrollments/" + paid.getId() + "/revoke").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
		// "other" đang có đơn chờ thanh toán khóa này
		Instant now = Instant.now();
		tx(() -> claimService.claimForOrder(other.getId(), course.getId(), 812, now, now.plus(30, ChronoUnit.MINUTES)));
		mvc.perform(post("/api/admin/learning/courses/" + course.getId() + "/enrollments").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + other.getEmail() + "\"}"))
				.andExpect(status().isConflict());
	}

	// ------------------------------------------------------------------ bình luận video

	@Test
	void notPurchasedCannotCommentAndLearnerCannotOpenInbox() throws Exception {
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId() + "/comments").with(as(learner)))
				.andExpect(status().isForbidden());
		mvc.perform(comment(lesson1, learner, "Em hỏi", null)).andExpect(status().isForbidden());
		mvc.perform(get("/api/learning/comments/inbox").with(as(learner))).andExpect(status().isForbidden());
		buyViaD(learner, 801);
		mvc.perform(comment(lesson1, learner, "   ", null)).andExpect(status().isBadRequest());
	}

	@Test
	void learnerCommentNotifiesSupportRolesAndReplyNotifiesLearner() throws Exception {
		User staff = newUser(Role.STAFF);
		User instructor = newUser(Role.INSTRUCTOR);
		buyViaD(learner, 802);
		String body = mvc.perform(comment(lesson1, learner, "Cô ơi pha màu nước thế nào ạ?", null))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.authorRole").value("USER"))
				.andReturn().getResponse().getContentAsString();
		long rootId = Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));

		// Admin, Nhân viên, Giảng viên đều nhận thông báo và thấy trong hộp thư "chưa trả lời"
		for (User u : List.of(admin, staff, instructor)) {
			awaitNotification(u, "NEW_COMMENT");
		}
		mvc.perform(get("/api/learning/comments/inbox").param("unanswered", "true").with(as(staff)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[?(@.comment.id == " + rootId + ")]").exists());

		// Giảng viên xem được bình luận dù không mua khóa, và trả lời
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId() + "/comments").with(as(instructor)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.canModerate").value(true))
				.andExpect(jsonPath("$.data.threads", hasSize(1)));
		mvc.perform(comment(lesson1, instructor, "Em pha loãng với nhiều nước nhé", rootId))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.parentId").value(rootId));
		awaitNotification(learner, "COMMENT_REPLY");

		// Đã có người hỗ trợ trả lời → không còn trong danh sách "chưa trả lời"
		mvc.perform(get("/api/learning/comments/inbox").param("unanswered", "true").with(as(staff)))
				.andExpect(jsonPath("$.data.items[?(@.comment.id == " + rootId + ")]").doesNotExist());

		// Học viên thấy luồng có 1 trả lời; học viên khác chưa mua không xem được
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId() + "/comments").with(as(learner)))
				.andExpect(jsonPath("$.data.canModerate").value(false))
				.andExpect(jsonPath("$.data.threads[0].replies", hasSize(1)))
				.andExpect(jsonPath("$.data.threads[0].replies[0].authorRole").value("INSTRUCTOR"));
		mvc.perform(get("/api/learning/lessons/" + lesson1.getId() + "/comments").with(as(other)))
				.andExpect(status().isForbidden());

		// Trả lời vào một trả lời vẫn gắn về bình luận gốc (một tầng)
		long replyId = Long.parseLong(mvc.perform(comment(lesson1, learner, "Em cảm ơn ạ", rootId))
				.andReturn().getResponse().getContentAsString().replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
		mvc.perform(comment(lesson1, staff, "Chúc em học tốt", replyId))
				.andExpect(jsonPath("$.data.parentId").value(rootId));
		// Bình luận của bài khác không trả lời chéo được
		mvc.perform(comment(lesson2, staff, "Sai bài", rootId)).andExpect(status().isNotFound());
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder comment(CourseLesson l, User u,
			String text, Long parentId) {
		String json = "{\"body\":\"" + text + "\",\"parentId\":" + (parentId == null ? "null" : parentId) + "}";
		return post("/api/learning/lessons/" + l.getId() + "/comments").with(as(u))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	// ------------------------------------------------------------------ admin soạn đề

	@Test
	void adminCanManageAssignmentsAndLearnerCannot() throws Exception {
		String req = "{\"title\":\"Đề mới\",\"instructions\":\"Vẽ cái ly\",\"required\":false,\"active\":true}";
		mvc.perform(put("/api/admin/lessons/" + lesson1.getId() + "/assignment").with(as(learner))
				.contentType(MediaType.APPLICATION_JSON).content(req)).andExpect(status().isForbidden());
		mvc.perform(put("/api/admin/lessons/" + lesson1.getId() + "/assignment").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content(req)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.title").value("Đề mới"));
		mvc.perform(get("/api/admin/learning/courses/" + course.getId() + "/assignments").with(as(admin)))
				.andExpect(jsonPath("$.data", hasSize(3)));
	}

	// ------------------------------------------------------------------ helpers

	private void buyViaD(User u, int orderId) {
		Instant now = Instant.now();
		tx(() -> claimService.claimForOrder(u.getId(), course.getId().intValue(), orderId, now,
				now.plus(30, ChronoUnit.MINUTES)));
		tx(() -> claimService.confirmCourseForOrder(u.getId(), course.getId().intValue(), orderId, now));
	}

	private void learnerEnroll(Integer courseId) {
		Integer profileId = profiles.learnerProfileId(learner.getId());
		var e = new Enrollment();
		e.setUserId(learner.getId());
		e.setLearnerProfileId(profileId);
		e.setCourseId(courseId);
		e.setSourceType(Enrollment.Source.MANUAL);
		enrollments.save(e);
	}

	private void tx(Runnable r) {
		new TransactionTemplate(txManager).executeWithoutResult(s -> r.run());
	}

	/** Xem hết video theo từng đoạn 5 giây (server giới hạn số giây được cộng theo thời gian thực). */
	private void watchFully(CourseLesson l) throws Exception {
		// lessons trong test dài 4 giây: lần báo đầu được cộng tối đa 30 giây nên một lần là đủ
		mvc.perform(put("/api/learning/lessons/" + l.getId() + "/progress").with(as(learner))
				.contentType(MediaType.APPLICATION_JSON).content("{\"positionSeconds\":4,\"watchedDeltaSeconds\":4}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.completed").value(true));
	}

	private long submitAndGetId(User u) throws Exception {
		String body = mvc.perform(submission(lesson2, u)).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
	}

	private org.springframework.test.web.servlet.RequestBuilder submission(CourseLesson l, User u) throws Exception {
		BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(img, "png", png);
		return multipart("/api/learning/lessons/" + l.getId() + "/submissions")
				.file(new MockMultipartFile("image", "bai.png", "image/png", png.toByteArray()))
				.param("note", "Bài của em").with(as(u));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder review(long id, String decision,
			String feedback) {
		String fb = feedback == null ? "null" : "\"" + feedback + "\"";
		return post("/api/admin/submissions/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
				.content("{\"decision\":\"" + decision + "\",\"feedback\":" + fb + ",\"score\":9}");
	}

	private Certificate awaitEmailStatus(Integer profileId, Certificate.EmailStatus expected) throws Exception {
		for (int i = 0; i < 100; i++) {
			var list = certificates.findByLearnerProfileIdOrderByIssuedAtDesc(profileId);
			if (!list.isEmpty() && list.get(0).getEmailStatus() == expected) {
				return list.get(0);
			}
			Thread.sleep(50);
		}
		throw new AssertionError("email status did not become " + expected);
	}

	private RequestPostProcessor as(User u) {
		return user(new AuthenticatedUserPrincipal(u.getId(), u.getEmail(), "x", u.getRole(), true));
	}

	private User newUser(Role role) {
		return users.save(User.builder().fullName("Người dùng " + role).email(UUID.randomUUID() + "@test.vn")
				.passwordHash("x").role(role).isActive(true).build());
	}

	private CourseLesson lesson(CourseModule m, String title, int order, boolean required) {
		CourseLesson l = new CourseLesson(m, title, order);
		l.setVideoUrl("https://example.com/v.mp4");
		l.setDurationSeconds(4);
		l.setRequired(required);
		return lessons.save(l);
	}

	private Course copyOf(String title) {
		Course c = new Course();
		c.setCode("C-" + UUID.randomUUID().toString().substring(0, 8));
		c.setSlug("c-" + UUID.randomUUID());
		c.setTitle(title);
		c.setPrice(50_000);
		c.setLevel(CourseLevel.BASIC);
		c.setStatus(CourseStatus.PUBLISHED);
		return c;
	}
}
