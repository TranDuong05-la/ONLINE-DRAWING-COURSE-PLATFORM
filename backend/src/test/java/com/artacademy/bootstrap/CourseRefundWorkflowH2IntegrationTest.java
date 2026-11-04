package com.artacademy.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.artacademy.ArtAcademyApplication;
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
import com.artacademy.learning.Enrollment;
import com.artacademy.learning.EnrollmentRepository;
import com.artacademy.learning.claim.CourseClaimRepository;
import com.artacademy.learning.claim.CourseClaimService;
import com.artacademy.payment.vnpay.VnPayRefundClient;
import com.artacademy.repository.UserRepository;
import com.artacademy.security.AuthenticatedUserPrincipal;
import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts;
import com.drawingcourse.backend.repository.RefundEventRepository;
import com.drawingcourse.backend.repository.RefundRequestRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;

import jakarta.persistence.EntityManager;

/**
 * Hoàn tiền khóa học từ đầu đến cuối (H2, Spring Security thật, VNPay được mock):
 * yêu cầu → duyệt → VNPay hoàn tiền → C thu hồi quyền học; VNPay lỗi → hoàn thủ công; từ chối; hết hạn; gửi trùng.
 */
@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
		"spring.datasource.url=jdbc:h2:mem:course_refund_flow;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"app.seed.enabled=false",
		"app.storage.root=target/test-refund-storage" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseRefundWorkflowH2IntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired EntityManager em;
	@Autowired PlatformTransactionManager txManager;
	@Autowired UserRepository users;
	@Autowired CourseRepository courses;
	@Autowired CourseModuleRepository modules;
	@Autowired CourseLessonRepository lessons;
	@Autowired CourseClaimService claims;
	@Autowired CourseClaimRepository claimRepository;
	@Autowired EnrollmentRepository enrollments;
	@Autowired RefundRequestRepository refunds;
	@Autowired RefundEventRepository refundEvents;
	@MockitoBean VnPayRefundClient vnPay;

	User learner;
	User other;
	User admin;

	@BeforeEach
	void setUp() {
		reset(vnPay);
		learner = newUser(Role.USER);
		other = newUser(Role.USER);
		admin = newUser(Role.ADMIN);
	}

	@Test
	void approvedRefundIsPaidByVnPayAndRevokesCourseAccess() throws Exception {
		Purchase p = purchase(learner, Instant.now().minus(1, ChronoUnit.DAYS));
		when(vnPay.refund(any())).thenReturn(new VnPayRefundClient.RefundResult(VnPayRefundClient.Outcome.SUCCESS,
				"00", "Success", "vnpay-refund:999", "b".repeat(64)));

		mvc.perform(get("/api/refunds/eligibility").param("orderId", p.orderId + "").param("orderItemId", p.itemId + "")
				.with(as(learner))).andExpect(status().isOk()).andExpect(jsonPath("$.data.eligible").value(true));
		long id = createRefund(learner, p, "key-1");
		mvc.perform(get("/api/refunds").with(as(learner))).andExpect(jsonPath("$.data[0].status").value("PENDING"));

		mvc.perform(post("/api/admin/refunds/" + id + "/approve").with(as(learner))).andExpect(status().isForbidden());
		mvc.perform(post("/api/admin/refunds/" + id + "/approve").with(as(admin)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REFUNDED"))
				.andExpect(jsonPath("$.data.courseAccessStatus").value("REVOKED"));

		assertThat(enrollmentOf(learner, p.courseId).getStatus()).isEqualTo(Enrollment.Status.REVOKED);
		assertThat(claimRepository.findByOrderItemIdOrderByCourseIdAsc(p.itemId).getFirst().getStatus().name())
				.isEqualTo("REFUNDED");
		assertThat(refundEvents.findAllByRefundRequestIdOrderById(id)).extracting(RefundEventJpaEntity::getOperationType)
				.containsExactly("ADMIN_APPROVAL", "PROVIDER_OUTCOME", "COURSE_ACCESS_REVOCATION");
		mvc.perform(get("/api/learning/courses/" + p.courseId).with(as(learner))).andExpect(status().isForbidden());
	}

	@Test
	void vnPayFailureCanBeSettledManuallyAndStillRevokesAccess() throws Exception {
		Purchase p = purchase(learner, Instant.now().minus(2, ChronoUnit.HOURS));
		when(vnPay.refund(any())).thenReturn(new VnPayRefundClient.RefundResult(
				VnPayRefundClient.Outcome.DEFINITIVE_FAILURE, "91", "Transaction not found", null, "c".repeat(64)));
		long id = createRefund(learner, p, "key-2");
		mvc.perform(post("/api/admin/refunds/" + id + "/approve").with(as(admin)))
				.andExpect(jsonPath("$.data.status").value("FAILED"));
		assertThat(enrollmentOf(learner, p.courseId).getStatus()).isEqualTo(Enrollment.Status.ACTIVE);

		mvc.perform(post("/api/admin/refunds/" + id + "/confirm-manual").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"CK Vietcombank 123456\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REFUNDED"));
		assertThat(enrollmentOf(learner, p.courseId).getStatus()).isEqualTo(Enrollment.Status.REVOKED);

		// Ghi chú trùng với lần trước vẫn phải hoàn được (mã tham chiếu không lấy từ ghi chú)
		Purchase p2 = purchase(learner, Instant.now().minus(1, ChronoUnit.HOURS));
		long id2 = createRefund(learner, p2, "key-2b");
		mvc.perform(post("/api/admin/refunds/" + id2 + "/approve").with(as(admin)))
				.andExpect(jsonPath("$.data.status").value("FAILED"));
		mvc.perform(post("/api/admin/refunds/" + id2 + "/confirm-manual").with(as(admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"CK Vietcombank 123456\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REFUNDED"));
		assertThat(enrollmentOf(learner, p2.courseId).getStatus()).isEqualTo(Enrollment.Status.REVOKED);
	}

	@Test
	void unknownVnPayResultIsResolvedByAdminAfterCheckingPortal() throws Exception {
		when(vnPay.refund(any())).thenReturn(new VnPayRefundClient.RefundResult(
				VnPayRefundClient.Outcome.UNKNOWN, "99", "Unexpected error", null, "d".repeat(64)));
		String json = "application/json";

		// Admin xác nhận VNPay CHƯA hoàn → FAILED → chuyển khoản thủ công → REFUNDED
		Purchase p1 = purchase(learner, Instant.now().minus(2, ChronoUnit.HOURS));
		long id1 = createRefund(learner, p1, "key-u1");
		mvc.perform(post("/api/admin/refunds/" + id1 + "/approve").with(as(admin)))
				.andExpect(jsonPath("$.data.status").value("VERIFYING"));
		mvc.perform(post("/api/admin/refunds/" + id1 + "/confirm-manual").with(as(admin)).contentType(json)
				.content("{\"note\":\"CK 1\"}")).andExpect(status().isConflict());
		mvc.perform(post("/api/admin/refunds/" + id1 + "/resolve").with(as(admin)).contentType(json)
				.content("{\"note\":\"x\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/admin/refunds/" + id1 + "/resolve").with(as(learner)).contentType(json)
				.content("{\"refunded\":false,\"note\":\"x\"}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/admin/refunds/" + id1 + "/resolve").with(as(admin)).contentType(json)
				.content("{\"refunded\":false,\"note\":\"Portal: không có giao dịch hoàn\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"));
		assertThat(enrollmentOf(learner, p1.courseId).getStatus()).isEqualTo(Enrollment.Status.ACTIVE);
		mvc.perform(post("/api/admin/refunds/" + id1 + "/confirm-manual").with(as(admin)).contentType(json)
				.content("{\"note\":\"CK 2\"}")).andExpect(jsonPath("$.data.status").value("REFUNDED"));
		assertThat(enrollmentOf(learner, p1.courseId).getStatus()).isEqualTo(Enrollment.Status.REVOKED);

		// Admin thấy VNPay ĐÃ hoàn → REFUNDED ngay, thu hồi quyền học; chốt lần 2 bị chặn
		Purchase p2 = purchase(learner, Instant.now().minus(1, ChronoUnit.HOURS));
		long id2 = createRefund(learner, p2, "key-u2");
		mvc.perform(post("/api/admin/refunds/" + id2 + "/approve").with(as(admin)))
				.andExpect(jsonPath("$.data.status").value("VERIFYING"));
		mvc.perform(post("/api/admin/refunds/" + id2 + "/resolve").with(as(admin)).contentType(json)
				.content("{\"refunded\":true,\"note\":\"Portal: hoàn thành công\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REFUNDED"));
		assertThat(enrollmentOf(learner, p2.courseId).getStatus()).isEqualTo(Enrollment.Status.REVOKED);
		mvc.perform(post("/api/admin/refunds/" + id2 + "/resolve").with(as(admin)).contentType(json)
				.content("{\"refunded\":true,\"note\":\"again\"}")).andExpect(status().isConflict());
	}

	@Test
	void rejectionKeepsAccessAndLearnerMayAskAgain() throws Exception {
		Purchase p = purchase(learner, Instant.now().minus(3, ChronoUnit.DAYS));
		long id = createRefund(learner, p, "key-3");
		mvc.perform(post("/api/admin/refunds/" + id + "/reject").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"KHONG_DU_DIEU_KIEN\"}")).andExpect(jsonPath("$.data.status").value("REJECTED"));
		verify(vnPay, never()).refund(any());
		assertThat(enrollmentOf(learner, p.courseId).getStatus()).isEqualTo(Enrollment.Status.ACTIVE);
		mvc.perform(get("/api/refunds/eligibility").param("orderId", p.orderId + "").param("orderItemId", p.itemId + "")
				.with(as(learner))).andExpect(jsonPath("$.data.eligible").value(true));
	}

	@Test
	void expiredWindowDuplicateAndForeignOrderAreBlocked() throws Exception {
		Purchase old = purchase(learner, Instant.now().minus(8, ChronoUnit.DAYS));
		mvc.perform(get("/api/refunds/eligibility").param("orderId", old.orderId + "").param("orderItemId", old.itemId + "")
				.with(as(learner))).andExpect(jsonPath("$.data.eligible").value(false))
				.andExpect(jsonPath("$.data.reason").value("COURSE_WINDOW_EXPIRED"));
		mvc.perform(refundRequest(learner, old, "key-old")).andExpect(status().isConflict());

		Purchase fresh = purchase(learner, Instant.now().minus(1, ChronoUnit.HOURS));
		long id = createRefund(learner, fresh, "key-4");
		// gửi lại đúng Idempotency-Key: trả về yêu cầu cũ
		mvc.perform(refundRequest(learner, fresh, "key-4")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.id").value(id));
		// key khác cho cùng khóa: đã có yêu cầu đang mở
		mvc.perform(refundRequest(learner, fresh, "key-5")).andExpect(status().isConflict());
		// người khác không thấy / không yêu cầu được đơn của học viên
		mvc.perform(refundRequest(other, fresh, "key-6")).andExpect(status().isNotFound());
		mvc.perform(post("/api/refunds").with(as(learner)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"orderId\":" + fresh.orderId + ",\"orderItemId\":" + fresh.itemId + ",\"reasonCode\":\"X\"}"))
				.andExpect(status().isBadRequest());
		assertThat(refunds.findAllByUserIdOrderByRequestedAtDescIdDesc(learner.getId())).hasSize(1);
	}

	// ------------------------------------------------------------------ helpers

	private long createRefund(User u, Purchase p, String key) throws Exception {
		String body = mvc.perform(refundRequest(u, p, key)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("PENDING")).andReturn().getResponse().getContentAsString();
		return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refundRequest(User u, Purchase p,
			String key) {
		return post("/api/refunds").with(as(u)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content("{\"orderId\":" + p.orderId + ",\"orderItemId\":" + p.itemId
						+ ",\"reasonCode\":\"CHANGED_MIND\",\"note\":\"Không phù hợp\"}");
	}

	private record Purchase(Integer orderId, Integer itemId, Integer courseId) {
	}

	/** Đơn khóa học đã thanh toán: Order + PaymentAttempt SUCCEEDED + Settlement + claim CONFIRMED của C. */
	private Purchase purchase(User u, Instant paidAtRaw) {
		Instant paidAt = paidAtRaw.truncatedTo(ChronoUnit.SECONDS);
		Course course = newCourse();
		return new TransactionTemplate(txManager).execute(s -> {
			Instant created = paidAt.minusSeconds(120);
			var order = OrderJpaEntity.fromSnapshot(Order.create(u.getId(),
					List.of(new OrderLineSnapshot(OrderItemType.COURSE, course.getId(), course.getTitle(), 1, 199_000, true)),
					0, Optional.empty(), Clock.fixed(created, ZoneOffset.UTC)));
			em.persist(order);
			em.flush();
			var item = order.getItems().getFirst();
			var stored = new OrderPersistenceMapper().toDomain(order);
			var attempt = PaymentAttemptJpaEntity.newSnapshot(PaymentAttempt.create(order.getId(), stored, "vnpay",
					"refund-flow-" + UUID.randomUUID(), Clock.fixed(created.plusSeconds(5), ZoneOffset.UTC)));
			em.persist(attempt);
			em.flush();
			em.createNativeQuery("update payment_attempts set status='SUCCEEDED', transaction_reference=:txn, "
					+ "succeeded_at=:at, updated_at=:at where id=:id").setParameter("txn", String.valueOf(System.nanoTime() % 1_000_000_000L))
					.setParameter("at", paidAt).setParameter("id", attempt.getId()).executeUpdate();
			em.createNativeQuery("update orders set status='CONFIRMED' where id=:id").setParameter("id", order.getId())
					.executeUpdate();
			var event = new PaymentEvent("vnpay", "evt-" + UUID.randomUUID(), attempt.getMerchantReference(),
					"txn-" + UUID.randomUUID(), stored.getTotalVnd(), "VND", PaymentEvent.Outcome.SUCCEEDED,
					paidAt, paidAt.plusSeconds(1), "a".repeat(64), "verified-test");
			var audit = new PaymentEventAudit(null, attempt.getId(), order.getId(), u.getId(), event, event.fingerprint(),
					PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, PaymentEventAudit.Disposition.APPLIED,
					PaymentAttempt.ReviewReason.NONE, OrderStatus.PENDING_PAYMENT);
			var paymentEvent = PaymentEventJpaEntity.newSnapshot(audit);
			em.persist(paymentEvent);
			em.flush();
			em.persist(PaymentSettlementJpaEntity.record(order.getId(), attempt.getId(), paymentEvent.getId(), paidAt,
					paidAt.plusSeconds(2)));
			em.flush();
			claims.reserve(new CourseClaimContracts.ReservationCommand(u.getId(), order.getId(),
					List.of(new CourseClaimContracts.ClaimLine(item.getId(), course.getId().longValue(),
							CourseClaimContracts.ClaimSourceType.STANDALONE_COURSE, null)),
					created, created.plusSeconds(1800)));
			claims.confirmCourseForOrder(u.getId(), course.getId(), order.getId(), item.getId(), paidAt);
			return new Purchase(order.getId(), item.getId(), course.getId());
		});
	}

	private Course newCourse() {
		Course c = new Course();
		c.setCode("RF-" + UUID.randomUUID().toString().substring(0, 8));
		c.setSlug("rf-" + UUID.randomUUID());
		c.setTitle("Khóa hoàn tiền");
		c.setPrice(199_000);
		c.setLevel(CourseLevel.BASIC);
		c.setStatus(CourseStatus.PUBLISHED);
		c = courses.save(c);
		CourseModule m = modules.save(new CourseModule(c, "Chương 1", 1));
		CourseLesson l = new CourseLesson(m, "Bài 1", 1);
		l.setVideoUrl("https://example.com/v.mp4");
		l.setDurationSeconds(600);
		l.setRequired(true);
		lessons.save(l);
		return c;
	}

	private Enrollment enrollmentOf(User u, Integer courseId) {
		return enrollments.findByCourseIdAndStatus(courseId, Enrollment.Status.ACTIVE).stream()
				.filter(e -> e.getUserId().equals(u.getId())).findFirst()
				.orElseGet(() -> enrollments.findByCourseIdAndStatus(courseId, Enrollment.Status.REVOKED).stream()
						.filter(e -> e.getUserId().equals(u.getId())).findFirst().orElseThrow());
	}

	private RequestPostProcessor as(User u) {
		return user(new AuthenticatedUserPrincipal(u.getId(), u.getEmail(), "x", u.getRole(), true));
	}

	private User newUser(Role role) {
		return users.save(User.builder().fullName("Người dùng " + role).email(UUID.randomUUID() + "@test.vn")
				.passwordHash("x").role(role).isActive(true).build());
	}
}
