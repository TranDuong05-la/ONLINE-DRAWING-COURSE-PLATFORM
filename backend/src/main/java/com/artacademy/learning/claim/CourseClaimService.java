package com.artacademy.learning.claim;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.learning.Enrollment;
import com.artacademy.learning.EnrollmentRepository;
import com.artacademy.learning.EnrollmentService;
import com.artacademy.learning.LearnerProfileService;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts.ClaimLine;
import com.drawingcourse.backend.service.expiration.CoursePurchaseReleasePort;
import com.drawingcourse.backend.service.settlement.CourseOwnershipConfirmationPort;

/**
 * Điểm nối của C cho D: quyền sở hữu khóa và "giữ chỗ" khóa theo Order.
 *
 * <p>Cài đặt các interface D đã định nghĩa sẵn: {@link CourseClaimContracts.CourseClaims} (bản đầy đủ, có OrderItem
 * và Combo), {@link CourseOwnershipConfirmationPort} (settlement) và {@link CoursePurchaseReleasePort} (Order hết hạn).
 * Cổng Checkout khóa lẻ {@code CoursePurchasePort} do {@link CoursePurchasePortAdapter} cung cấp (chỉ bật khi D bật
 * Checkout). {@code CourseOwnershipProvider} của giỏ hàng: D dùng {@code courseClaimService::isOwnedBy}
 * (C không đăng ký bean loại này để không trùng với bean D tự khai báo).
 *
 * <p>Quy ước đã thống nhất với D:
 * <ul>
 *   <li>D chỉ truyền userId; C tự đổi sang learnerProfileId.</li>
 *   <li>Mọi thao tác ghi dùng {@code Propagation.MANDATORY}: chạy trong transaction D mở, không tự commit riêng.
 *       Lỗi ở bất kỳ khóa nào làm rollback cả Order.</li>
 *   <li>Khóa (lock) theo thứ tự courseId tăng dần để hai Order không chờ nhau vòng tròn (deadlock).</li>
 *   <li>RESERVED → CONFIRMED / RELEASED; CONFIRMED → REFUNDED. Không release claim đã thanh toán.</li>
 *   <li>Refund chỉ thu hồi Enrollment do chính OrderItem đó cấp; quyền học có từ nguồn khác được giữ nguyên.</li>
 * </ul>
 * Thời gian từ D là {@link Instant}; C lưu dưới dạng LocalDateTime theo UTC trong bảng course_claims.
 */
@Service
public class CourseClaimService implements CourseClaimContracts.CourseClaims,
		CourseOwnershipConfirmationPort, CoursePurchaseReleasePort {

	private final CourseClaimRepository claims;
	private final EnrollmentRepository enrollments;
	private final EnrollmentService enrollmentService;
	private final LearnerProfileService profiles;

	public CourseClaimService(CourseClaimRepository claims, EnrollmentRepository enrollments,
			EnrollmentService enrollmentService, LearnerProfileService profiles) {
		this.claims = claims;
		this.enrollments = enrollments;
		this.enrollmentService = enrollmentService;
		this.profiles = profiles;
	}

	// ------------------------------------------------------------------ đọc

	@Transactional(readOnly = true)
	public boolean isOwnedBy(Integer authenticatedUserId, Integer courseId) {
		requirePositive(authenticatedUserId, "authenticatedUserId");
		requirePositive(courseId, "courseId");
		return enrollmentService.isOwned(authenticatedUserId, courseId);
	}

	/** Đang có claim RESERVED còn hạn của C cho khóa này (Order khác đang chờ thanh toán). */
	@Transactional(readOnly = true)
	public boolean hasActivePendingPurchase(Integer authenticatedUserId, Integer courseId, Instant observedAt) {
		requirePositive(authenticatedUserId, "authenticatedUserId");
		requirePositive(courseId, "courseId");
		Objects.requireNonNull(observedAt, "observedAt");
		Integer profileId = profiles.findLearnerProfileId(authenticatedUserId);
		return profileId != null
				&& !claims.findActivePendingCourseIds(profileId, List.of(courseId), utc(observedAt)).isEmpty();
	}

	@Override
	@Transactional(readOnly = true)
	public CourseClaimContracts.EligibilitySnapshot inspectEligibility(Integer authenticatedUserId,
			Set<Long> candidateCourseIds, Instant observedAt) {
		requirePositive(authenticatedUserId, "authenticatedUserId");
		Objects.requireNonNull(observedAt, "observedAt");
		Set<Long> requested = Set.copyOf(Objects.requireNonNull(candidateCourseIds, "candidateCourseIds"));
		// Hợp đồng của D dùng Long; id khóa học của B là Integer.
		Set<Integer> requestedIds = requested.stream().map(CourseClaimService::toCourseId).collect(Collectors.toSet());
		Set<Long> owned = toLongs(enrollmentService.ownedCourseIds(authenticatedUserId, requestedIds));
		Integer profileId = profiles.findLearnerProfileId(authenticatedUserId);
		Set<Long> pending = profileId == null || requested.isEmpty() ? Set.of()
				: toLongs(claims.findActivePendingCourseIds(profileId, requestedIds, utc(observedAt)));
		return new CourseClaimContracts.EligibilitySnapshot(requested, owned, pending);
	}

	// ------------------------------------------------------------------ giữ chỗ

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserve(CourseClaimContracts.ReservationCommand command) {
		Integer profileId = profiles.learnerProfileId(command.authenticatedUserId());
		for (ClaimLine line : sortedByCourse(command.lines())) {
			reserveOne(command.authenticatedUserId(), profileId, command.orderId(), line.orderItemId(),
					CourseClaim.SourceType.valueOf(line.sourceType().name()), line.componentPosition(), toCourseId(line.courseId()),
					command.observedAt(), command.expiresAt());
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void claimForOrder(Integer authenticatedUserId, Integer courseId, Integer orderId, Instant observedAt,
			Instant expiresAt) {
		requirePositive(authenticatedUserId, "authenticatedUserId");
		requirePositive(courseId, "courseId");
		requirePositive(orderId, "orderId");
		Objects.requireNonNull(observedAt, "observedAt");
		Objects.requireNonNull(expiresAt, "expiresAt");
		if (!observedAt.isBefore(expiresAt)) {
			throw new IllegalArgumentException("claim expiration must be after observation");
		}
		reserveOne(authenticatedUserId, profiles.learnerProfileId(authenticatedUserId), orderId, null,
				CourseClaim.SourceType.STANDALONE_COURSE, null, courseId, observedAt, expiresAt);
	}

	private void reserveOne(Integer userId, Integer profileId, Integer orderId, Integer orderItemId,
			CourseClaim.SourceType sourceType, Integer componentPosition, Integer courseId, Instant observedAt,
			Instant expiresAt) {
		CourseClaim same = claims.findForUpdate(orderId, courseId).orElse(null);
		if (same != null) {
			requireOwner(same, userId);
			if (same.getStatus() == CourseClaim.Status.RESERVED || same.getStatus() == CourseClaim.Status.CONFIRMED) {
				// gọi lại cho cùng Order: idempotent, nhưng phải đúng cùng OrderItem
				if (!Objects.equals(same.getOrderItemId(), orderItemId) || same.getSourceType() != sourceType
						|| !Objects.equals(same.getComponentPosition(), componentPosition)) {
					throw new IllegalStateException("Existing Course claim does not match the OrderItem identity");
				}
				return;
			}
			throw new CourseClaimConflictException(CourseClaimConflictException.Reason.CLAIM_ALREADY_CLOSED, courseId,
					"Claim của Order " + orderId + " cho khóa " + courseId + " đã kết thúc (" + same.getStatus() + ")");
		}
		Enrollment owned = enrollments.findForUpdate(profileId, courseId).orElse(null);
		if (owned != null && owned.isActive()) {
			throw new CourseClaimConflictException(CourseClaimConflictException.Reason.ALREADY_OWNED, courseId,
					"Học viên đã sở hữu khóa " + courseId);
		}
		String key = CourseClaim.activeKey(profileId, courseId);
		CourseClaim active = claims.findActiveForUpdate(key).orElse(null);
		LocalDateTime observed = utc(observedAt);
		if (active != null) {
			boolean expiredReservation = active.getStatus() == CourseClaim.Status.RESERVED
					&& !active.getExpiresAt().isAfter(observed);
			if (!expiredReservation) {
				throw new CourseClaimConflictException(CourseClaimConflictException.Reason.PENDING_IN_ANOTHER_ORDER,
						courseId, "Khóa " + courseId + " đang được giữ cho Order " + active.getOrderId());
			}
			// Order cũ đã quá hạn nhưng chưa được dọn: nhả chỗ để Order mới giữ.
			close(active, CourseClaim.Status.RELEASED, observed, "EXPIRED");
			claims.saveAndFlush(active);
		}
		CourseClaim c = new CourseClaim();
		c.setUserId(userId);
		c.setLearnerProfileId(profileId);
		c.setCourseId(courseId);
		c.setOrderId(orderId);
		c.setOrderItemId(orderItemId);
		c.setSourceType(sourceType);
		c.setComponentPosition(componentPosition);
		c.setStatus(CourseClaim.Status.RESERVED);
		c.setActiveKey(key);
		c.setReservedAt(observed);
		c.setExpiresAt(utc(expiresAt));
		try {
			claims.saveAndFlush(c);
		} catch (DataIntegrityViolationException concurrentClaim) {
			// Order khác vừa giữ cùng khóa (unique active_key). Transaction của D sẽ rollback toàn bộ.
			throw new CourseClaimConflictException(CourseClaimConflictException.Reason.PENDING_IN_ANOTHER_ORDER,
					courseId, "Khóa " + courseId + " vừa được giữ cho một Order khác");
		}
	}

	// ------------------------------------------------------------------ xác nhận (thanh toán thành công)

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void confirm(CourseClaimContracts.ConfirmationCommand command) {
		for (ClaimLine line : sortedByCourse(command.lines())) {
			confirmOne(command.authenticatedUserId(), toCourseId(line.courseId()), command.orderId(), line.orderItemId(),
					command.confirmedAt());
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void confirmForOrder(Integer authenticatedUserId, Integer courseId, Integer orderId, Instant confirmedAt) {
		requirePositive(courseId, "courseId");
		confirmOne(authenticatedUserId, courseId, orderId, null, confirmedAt);
	}

	/** Không kiểm tra OrderItem (Checkout khóa lẻ cũ / test). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void confirmCourseForOrder(Integer userId, Integer courseId, Integer orderId, Instant confirmedAt) {
		requirePositive(courseId, "courseId");
		confirmOne(userId, courseId, orderId, null, confirmedAt);
	}

	/** Settlement của D: xác nhận đúng claim của OrderItem đã lưu trong Order. */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void confirmCourseForOrder(Integer userId, Integer courseId, Integer orderId, Integer orderItemId,
			Instant confirmedAt) {
		requirePositive(courseId, "courseId");
		requirePositive(orderItemId, "orderItemId");
		confirmOne(userId, courseId, orderId, orderItemId, confirmedAt);
	}

	/**
	 * Settlement của D cho một Combo: xác nhận TOÀN BỘ claim đã giữ cho OrderItem combo (một claim cho mỗi khóa) và cấp quyền
	 * học từng khóa. Tập claim lưu lúc đặt hàng chính là bản chụp của combo; không đọc lại định nghĩa combo hiện tại.
	 * Chạy trong transaction của D nên lỗi ở một khóa làm rollback cả combo (không cấp nửa chừng).
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void confirmComboForOrder(Integer userId, Integer orderId, Integer orderItemId, Instant confirmedAt) {
		requirePositive(userId, "userId");
		requirePositive(orderId, "orderId");
		requirePositive(orderItemId, "orderItemId");
		for (CourseClaim c : comboClaims(orderId, orderItemId, true)) {
			confirmOne(userId, c.getCourseId(), orderId, orderItemId, confirmedAt);
		}
	}

	/** Nhả toàn bộ claim còn RESERVED của một Combo khi Order hết hạn. Idempotent; không nhả claim đã thanh toán. */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseComboForExpiredOrder(Integer userId, Integer orderId, Integer orderItemId, Instant releasedAt) {
		requirePositive(userId, "userId");
		requirePositive(orderId, "orderId");
		requirePositive(orderItemId, "orderItemId");
		for (CourseClaim c : comboClaims(orderId, orderItemId, false)) {
			releaseOne(userId, c.getCourseId(), orderId, "EXPIRED", releasedAt);
		}
	}

	/** Claim của một OrderItem combo theo thứ tự khóa tăng dần (khóa dòng theo thứ tự cố định để tránh deadlock). */
	private List<CourseClaim> comboClaims(Integer orderId, Integer orderItemId, boolean required) {
		List<CourseClaim> items = claims.findByOrderItemForUpdate(orderItemId);
		if (required && items.isEmpty()) {
			throw new IllegalStateException("Không có claim cho OrderItem combo " + orderItemId);
		}
		for (CourseClaim c : items) {
			if (!orderId.equals(c.getOrderId()) || c.getSourceType() != CourseClaim.SourceType.COMBO_COMPONENT) {
				throw new IllegalStateException("Claim của OrderItem " + orderItemId + " không phải thành phần combo của Order "
						+ orderId);
			}
		}
		return items;
	}

	/**
	 * Cấp quyền học theo yêu cầu của D sau khi thanh toán đã được xác minh. Idempotent.
	 * {@code expectedOrderItemId} khác null thì claim phải thuộc đúng OrderItem đó.
	 */
	private void confirmOne(Integer userId, Integer courseId, Integer orderId, Integer expectedOrderItemId,
			Instant confirmedAt) {
		requirePositive(userId, "userId");
		requirePositive(orderId, "orderId");
		Objects.requireNonNull(confirmedAt, "confirmedAt");
		CourseClaim c = claims.findForUpdate(orderId, courseId).orElseThrow(() -> new IllegalStateException(
				"Không có claim của Order " + orderId + " cho khóa " + courseId));
		requireOwner(c, userId);
		if (expectedOrderItemId != null && !expectedOrderItemId.equals(c.getOrderItemId())) {
			throw new IllegalStateException("Course claim OrderItem does not match the persisted Order item");
		}
		if (c.getStatus() == CourseClaim.Status.CONFIRMED) {
			Enrollment existing = enrollments.findForUpdate(c.getLearnerProfileId(), courseId).orElse(null);
			if (existing == null || !existing.isActive() || !Objects.equals(existing.getId(), c.getEnrollmentId())
					|| !Objects.equals(existing.getSourceOrderId(), c.getOrderId())
					|| !Objects.equals(existing.getSourceOrderItemId(), c.getOrderItemId())) {
				throw new IllegalStateException("Confirmed Course claim has inconsistent Enrollment provenance");
			}
			return;
		}
		if (c.getStatus() != CourseClaim.Status.RESERVED) {
			throw new IllegalStateException("Không thể xác nhận claim đang ở trạng thái " + c.getStatus());
		}
		LocalDateTime at = utc(confirmedAt);
		Enrollment e = enrollmentService.activate(userId, c.getLearnerProfileId(), courseId, Enrollment.Source.ORDER,
				orderId, c.getOrderItemId(), LocalDateTime.ofInstant(confirmedAt, java.time.ZoneId.systemDefault()));
		if (!e.isActive() || !Objects.equals(e.getSourceOrderId(), orderId)
				|| !Objects.equals(e.getSourceOrderItemId(), c.getOrderItemId())) {
			throw new IllegalStateException("Enrollment does not identify this Course purchase OrderItem");
		}
		c.setStatus(CourseClaim.Status.CONFIRMED);
		c.setConfirmedAt(at);
		c.setEnrollmentId(e.getId());
		claims.save(c);
	}

	// ------------------------------------------------------------------ nhả chỗ (Order hủy / hết hạn)

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(CourseClaimContracts.TerminalReleaseCommand command) {
		for (ClaimLine line : sortedByCourse(command.lines())) {
			releaseOne(command.authenticatedUserId(), toCourseId(line.courseId()), command.orderId(), command.reason().name(),
					command.releasedAt());
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseForOrder(Integer authenticatedUserId, Integer courseId, Integer orderId, String terminalReason,
			Instant releasedAt) {
		requirePositive(courseId, "courseId");
		releaseOne(authenticatedUserId, courseId, orderId, terminalReason, releasedAt);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseCourseForExpiredOrder(Integer userId, Integer courseId, Integer orderId, Instant releasedAt) {
		requirePositive(courseId, "courseId");
		releaseOne(userId, courseId, orderId, "EXPIRED", releasedAt);
	}

	private void releaseOne(Integer userId, Integer courseId, Integer orderId, String reason, Instant releasedAt) {
		requirePositive(userId, "userId");
		requirePositive(orderId, "orderId");
		Objects.requireNonNull(releasedAt, "releasedAt");
		CourseClaim c = claims.findForUpdate(orderId, courseId).orElse(null);
		if (c == null) {
			return; // không có gì để nhả (idempotent)
		}
		requireOwner(c, userId);
		switch (c.getStatus()) {
		case RELEASED -> {
			return;
		}
		case RESERVED -> {
			close(c, CourseClaim.Status.RELEASED, utc(releasedAt), reason == null ? "UNSPECIFIED" : reason);
			claims.save(c);
		}
		default -> throw new IllegalStateException(
				"Không được release claim đã thanh toán (" + c.getStatus() + "); dùng refundOrderItem");
		}
	}

	// ------------------------------------------------------------------ hoàn tiền

	/**
	 * Thu hồi quyền học của toàn bộ claim thuộc OrderItem (khóa lẻ hoặc cả Combo). Chỉ gọi khi kết quả hoàn tiền
	 * ĐÃ được xác nhận. Điều kiện được hoàn tiền do D quyết định dựa trên {@link CourseRefundEvidenceService}.
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void refundOrderItem(CourseClaimContracts.RefundOrderItemCommand command) {
		List<CourseClaim> items = claims.findByOrderItemForUpdate(command.orderItemId());
		if (items.isEmpty()) {
			throw new IllegalStateException("Không có claim cho OrderItem " + command.orderItemId());
		}
		for (CourseClaim c : items) {
			requireOwner(c, command.authenticatedUserId());
			if (!c.getOrderId().equals(command.orderId())) {
				throw new IllegalStateException("OrderItem " + command.orderItemId() + " không thuộc Order "
						+ command.orderId());
			}
			if (c.getStatus() != CourseClaim.Status.CONFIRMED && c.getStatus() != CourseClaim.Status.REFUNDED) {
				throw new IllegalStateException("Chỉ hoàn tiền claim đã thanh toán, khóa " + c.getCourseId() + " đang "
						+ c.getStatus());
			}
		}
		LocalDateTime at = utc(command.refundCompletedAt());
		for (CourseClaim c : items) {
			if (c.getStatus() == CourseClaim.Status.REFUNDED) {
				continue; // đã hoàn trước đó (gọi lại): idempotent
			}
			Enrollment e = enrollments.findForUpdate(c.getLearnerProfileId(), c.getCourseId()).orElse(null);
			boolean grantedByThisItem = e != null && Objects.equals(e.getSourceOrderId(), c.getOrderId())
					&& Objects.equals(e.getSourceOrderItemId(), c.getOrderItemId());
			if (grantedByThisItem) {
				enrollmentService.revoke(e, "REFUND " + command.refundOperationId(),
						LocalDateTime.ofInstant(command.refundCompletedAt(), java.time.ZoneId.systemDefault()));
			}
			c.setStatus(CourseClaim.Status.REFUNDED);
			c.setActiveKey(null);
			c.setRefundedAt(at);
			c.setRefundOperationId(command.refundOperationId());
			claims.save(c);
		}
	}

	// ------------------------------------------------------------------ helpers

	private static void close(CourseClaim c, CourseClaim.Status status, LocalDateTime at, String reason) {
		c.setStatus(status);
		c.setActiveKey(null);
		c.setReleasedAt(at);
		c.setReleaseReason(reason.length() > 50 ? reason.substring(0, 50) : reason);
	}

	private static void requireOwner(CourseClaim c, Integer userId) {
		if (!c.getUserId().equals(userId)) {
			throw new SecurityException("Course claim owner mismatch");
		}
	}

	private static List<ClaimLine> sortedByCourse(List<ClaimLine> lines) {
		return lines.stream().sorted(Comparator.comparing(ClaimLine::courseId)).toList();
	}

	/** Hợp đồng của D truyền id khóa học kiểu Long; B lưu id kiểu INT. */
	static Integer toCourseId(Long courseId) {
		return courseId == null ? null : Math.toIntExact(courseId);
	}

	private static Set<Long> toLongs(java.util.Collection<Integer> ids) {
		return ids.stream().map(Integer::longValue).collect(Collectors.toCollection(HashSet::new));
	}

	static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static void requirePositive(Integer value, String name) {
		if (value == null || value <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
