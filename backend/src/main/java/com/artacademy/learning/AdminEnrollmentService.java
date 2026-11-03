package com.artacademy.learning;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.course.CourseRepository;
import com.artacademy.entity.User;
import com.artacademy.learning.LearningDtos.AdminEnrollmentDto;
import com.artacademy.learning.claim.CourseClaimService;
import com.artacademy.repository.UserRepository;

/**
 * Admin cấp / thu hồi quyền học thủ công (không qua thanh toán), ví dụ học bổng, hỗ trợ học viên.
 *
 * <ul>
 * <li>Không cấp khi học viên đang có Order chờ thanh toán khóa đó: nếu cấp, lúc D xác nhận thanh toán quyền học sẽ
 * không khớp OrderItem và settlement của D báo lỗi.</li>
 * <li>Chỉ thu hồi được quyền cấp tay. Quyền từ Order phải thu hồi qua hoàn tiền của D (theo OrderItem).</li>
 * </ul>
 */
@Service
public class AdminEnrollmentService {
	private final EnrollmentRepository enrollments;
	private final EnrollmentService enrollmentService;
	private final CourseRepository courses;
	private final UserRepository users;
	private final LearnerProfileService profiles;
	private final CourseClaimService claims;

	public AdminEnrollmentService(EnrollmentRepository enrollments, EnrollmentService enrollmentService,
			CourseRepository courses, UserRepository users, LearnerProfileService profiles, CourseClaimService claims) {
		this.enrollments = enrollments;
		this.enrollmentService = enrollmentService;
		this.courses = courses;
		this.users = users;
		this.profiles = profiles;
		this.claims = claims;
	}

	/** Học viên của một khóa: đang học trước, mới cấp trước. */
	@Transactional(readOnly = true)
	public List<AdminEnrollmentDto> list(Integer courseId) {
		requireCourse(courseId);
		List<Enrollment> all = enrollments.findByCourseIdOrderByEnrolledAtDescIdDesc(courseId);
		Map<Integer, User> byId = users.findAllById(all.stream().map(Enrollment::getUserId).distinct().toList())
				.stream().collect(Collectors.toMap(User::getId, Function.identity()));
		return all.stream()
				.sorted(Comparator.comparing((Enrollment e) -> !e.isActive()))
				.map(e -> toDto(e, byId.get(e.getUserId())))
				.toList();
	}

	@Transactional
	public AdminEnrollmentDto grant(Integer courseId, String email) {
		requireCourse(courseId);
		String normalized = email == null ? "" : email.trim().toLowerCase();
		User user = users.findByEmail(normalized).orElseThrow(() -> Errors.notFound("Không có tài khoản " + normalized));
		if (!Boolean.TRUE.equals(user.getIsActive())) {
			throw Errors.conflict("Tài khoản " + normalized + " đang bị khóa");
		}
		Integer profileId = profiles.learnerProfileId(user.getId());
		Enrollment existing = enrollments.findForUpdate(profileId, courseId).orElse(null);
		if (existing != null && existing.isActive()) {
			throw Errors.conflict("Học viên đã có quyền học khóa này");
		}
		if (claims.hasActivePendingPurchase(user.getId(), courseId, Instant.now())) {
			throw Errors.conflict("Học viên đang có đơn chờ thanh toán khóa này, không cấp tay được");
		}
		Enrollment e = enrollmentService.activate(user.getId(), profileId, courseId, Enrollment.Source.MANUAL, null,
				null, LocalDateTime.now());
		return toDto(e, user);
	}

	@Transactional
	public AdminEnrollmentDto revoke(Long enrollmentId, String reason) {
		Enrollment found = enrollments.findById(enrollmentId)
				.orElseThrow(() -> Errors.notFound("Không tìm thấy quyền học"));
		Enrollment e = enrollments.findForUpdate(found.getLearnerProfileId(), found.getCourseId()).orElseThrow();
		if (!e.isActive()) {
			throw Errors.conflict("Quyền học này đã bị thu hồi");
		}
		if (e.getSourceType() != Enrollment.Source.MANUAL) {
			throw Errors.conflict("Quyền học từ đơn hàng chỉ thu hồi được qua hoàn tiền");
		}
		String why = reason == null || reason.isBlank() ? "ADMIN_REVOKED" : "ADMIN: " + reason.strip();
		enrollmentService.revoke(e, why, LocalDateTime.now());
		return toDto(e, users.findById(e.getUserId()).orElse(null));
	}

	private void requireCourse(Integer courseId) {
		if (courseId == null || !courses.existsById(courseId)) {
			throw Errors.notFound("Không tìm thấy khóa học");
		}
	}

	private static AdminEnrollmentDto toDto(Enrollment e, User u) {
		return new AdminEnrollmentDto(e.getId(), e.getUserId(), u == null ? "" : u.getFullName(),
				u == null ? "" : u.getEmail(), e.getStatus().name(), e.getSourceType().name(), e.getEnrolledAt(),
				Boolean.TRUE.equals(e.getCompleted()), e.getRevokedAt(), e.getRevokeReason());
	}
}
