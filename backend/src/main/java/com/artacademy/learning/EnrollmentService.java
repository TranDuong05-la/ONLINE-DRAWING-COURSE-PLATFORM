package com.artacademy.learning;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.course.CourseRepository;
import com.artacademy.learning.notification.LearningNotification;
import com.artacademy.learning.notification.NotificationRequest;

/**
 * Quyền học. Đây là CỔNG DUY NHẤT trước khi trả nội dung học: mọi API xem bài, lưu tiến độ, nộp bài đều gọi
 * {@link #requireActiveEnrollment} trước tiên.
 *
 * <p>Quyền học được tạo khi D xác nhận thanh toán (qua {@code CourseClaimService.confirm...}) hoặc cấp tay
 * ({@link #grantManually}, dùng cho dữ liệu mẫu/admin). Không có API HTTP nào để học viên tự cấp quyền.
 */
@Service
public class EnrollmentService {
	private final EnrollmentRepository enrollments;
	private final CourseRepository courses;
	private final LearnerProfileService profiles;
	private final ApplicationEventPublisher events;

	public EnrollmentService(EnrollmentRepository enrollments, CourseRepository courses,
			LearnerProfileService profiles, ApplicationEventPublisher events) {
		this.enrollments = enrollments;
		this.courses = courses;
		this.profiles = profiles;
		this.events = events;
	}

	/** Enrollment còn hiệu lực của user, hoặc 403. Chưa mua / đã bị thu hồi (refund) đều không học được. */
	@Transactional(readOnly = true)
	public Enrollment requireActiveEnrollment(Integer userId, Integer courseId) {
		Integer profileId = profiles.findLearnerProfileId(userId);
		if (profileId == null) {
			throw Errors.forbidden("Bạn chưa mua khóa học này");
		}
		return enrollments.findByLearnerProfileIdAndCourseId(profileId, courseId).filter(Enrollment::isActive)
				.orElseThrow(() -> Errors.forbidden("Bạn chưa mua khóa học này"));
	}

	@Transactional(readOnly = true)
	public boolean isOwned(Integer userId, Integer courseId) {
		Integer profileId = profiles.findLearnerProfileId(userId);
		return profileId != null && enrollments.findByLearnerProfileIdAndCourseId(profileId, courseId)
				.map(Enrollment::isActive).orElse(false);
	}

	@Transactional(readOnly = true)
	public Set<Integer> ownedCourseIds(Integer userId, Collection<Integer> courseIds) {
		Integer profileId = profiles.findLearnerProfileId(userId);
		if (profileId == null || courseIds.isEmpty()) {
			return Set.of();
		}
		return enrollments.findByLearnerProfileIdAndStatusAndCourseIdIn(profileId, Enrollment.Status.ACTIVE, courseIds)
				.stream().map(Enrollment::getCourseId).collect(Collectors.toSet());
	}

	/**
	 * Tạo hoặc kích hoạt lại quyền học trong transaction của người gọi (đã khóa dòng enrollment nếu có).
	 * Đã có quyền ACTIVE thì giữ nguyên (idempotent) và không ghi đè nguồn cấp cũ.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Enrollment activate(Integer userId, Integer learnerProfileId, Integer courseId, Enrollment.Source source,
			Integer orderId, Integer orderItemId, LocalDateTime at) {
		String courseTitle = courses.findById(courseId).map(c -> c.getTitle())
				.orElseThrow(() -> Errors.notFound("Không tìm thấy khóa học " + courseId));
		Enrollment e = enrollments.findForUpdate(learnerProfileId, courseId).orElse(null);
		if (e != null && e.isActive()) {
			return e;
		}
		if (e == null) {
			e = new Enrollment();
			e.setUserId(userId);
			e.setLearnerProfileId(learnerProfileId);
			e.setCourseId(courseId);
		}
		// Mua lại sau khi bị thu hồi: dùng lại dòng cũ, tiến độ cũ vẫn còn trong user_lesson_progress.
		e.setStatus(Enrollment.Status.ACTIVE);
		e.setSourceType(source);
		e.setSourceOrderId(orderId);
		e.setSourceOrderItemId(orderItemId);
		e.setEnrolledAt(at);
		e.setRevokedAt(null);
		e.setRevokeReason(null);
		e = enrollments.saveAndFlush(e);
		events.publishEvent(NotificationRequest.toUser(userId, LearningNotification.Type.COURSE_ENROLLED,
				"ENROLL:" + e.getId() + ":" + at, "Bạn đã có quyền học khóa mới", courseTitle, courseId, null, courseId.longValue()));
		return e;
	}

	/** Thu hồi quyền học (refund). Chỉ gọi trong transaction đã khóa dòng enrollment. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void revoke(Enrollment e, String reason, LocalDateTime at) {
		if (!e.isActive()) {
			return;
		}
		e.setStatus(Enrollment.Status.REVOKED);
		e.setRevokedAt(at);
		e.setRevokeReason(reason == null ? null : reason.substring(0, Math.min(150, reason.length())));
		enrollments.save(e);
	}

	/** Cấp quyền học thủ công (dữ liệu mẫu / admin), không qua thanh toán. */
	@Transactional
	public List<Integer> grantManually(Integer userId, Collection<Integer> courseIds) {
		Integer profileId = profiles.learnerProfileId(userId);
		LocalDateTime now = LocalDateTime.now();
		return courseIds.stream().distinct().sorted()
				.map(id -> activate(userId, profileId, id, Enrollment.Source.MANUAL, null, null, now).getCourseId())
				.toList();
	}
}
