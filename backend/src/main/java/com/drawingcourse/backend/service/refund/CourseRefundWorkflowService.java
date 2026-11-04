package com.drawingcourse.backend.service.refund;

import static com.drawingcourse.backend.entity.RefundPersistenceTypes.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.artacademy.common.ApiException;
import com.artacademy.payment.vnpay.VnPayRefundClient;
import com.drawingcourse.backend.entity.OrderItemJpaEntity;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.RefundEligibilityPolicy;
import com.drawingcourse.backend.entity.RefundEventJpaEntity;
import com.drawingcourse.backend.entity.RefundRequestJpaEntity;
import com.drawingcourse.backend.entity.RefundStateMachine;
import com.drawingcourse.backend.integration.contract.CourseClaimContracts;
import com.drawingcourse.backend.integration.contract.CourseRefundContracts;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.repository.PaymentSettlementRepository;
import com.drawingcourse.backend.repository.RefundProviderAttemptRepository;
import com.drawingcourse.backend.repository.RefundRequestRepository;
import com.drawingcourse.backend.repository.RefundUnitAllocationRepository;

/**
 * Hoàn tiền KHÓA HỌC LẺ từ đầu đến cuối, theo đúng các bước của {@link RefundStateMachine}:
 *
 * <ol>
 * <li>Học viên xem điều kiện ({@link RefundEligibilityPolicy}: trong 7 ngày kể từ lúc thanh toán, học ≤ 20%, chưa nộp
 * bài) với bằng chứng do C cung cấp, rồi gửi yêu cầu (giữ chỗ số tiền, chống gửi trùng bằng Idempotency-Key).</li>
 * <li>Admin duyệt: C kiểm tra lại bằng chứng CÓ KHÓA DÒNG; còn đủ điều kiện mới ghi APPROVED + READY.</li>
 * <li>Trả tiền qua VNPay (ngoài transaction). Thành công → SUCCEEDED, C thu hồi đúng quyền học do OrderItem này cấp
 * → REVOKED. Thất bại dứt khoát → FAILED (admin thử lại hoặc xác nhận đã hoàn thủ công). Không rõ → UNKNOWN.</li>
 * </ol>
 * Mỗi bước ghi một sự kiện vào refund_events. Combo và học cụ chưa hỗ trợ ở đây (chưa mua được).
 */
@Service
public class CourseRefundWorkflowService {
	static final String PROVIDER_VNPAY = "vnpay";
	static final String PROVIDER_MANUAL = "manual-transfer";

	private final RefundRequestRepository requests;
	private final RefundUnitAllocationRepository allocations;
	private final RefundProviderAttemptRepository providerAttempts;
	private final RefundAuditedRequestService auditedRequests;
	private final RefundAdminReviewService adminReview;
	private final RefundPersistenceService persistence;
	private final RefundEventStoreService eventStore;
	private final OrderRepository orders;
	private final PaymentSettlementRepository settlements;
	private final PaymentAttemptRepository paymentAttempts;
	private final CourseRefundContracts.CourseRefundEvidencePort courseEvidence;
	private final CourseClaimContracts.CourseClaims courseClaims;
	private final ObjectProvider<VnPayRefundClient> vnPay;
	private final TransactionTemplate tx;
	private final RefundEligibilityPolicy policy = new RefundEligibilityPolicy();

	public CourseRefundWorkflowService(RefundRequestRepository requests, RefundUnitAllocationRepository allocations,
			RefundProviderAttemptRepository providerAttempts, RefundAuditedRequestService auditedRequests,
			RefundAdminReviewService adminReview, RefundPersistenceService persistence, RefundEventStoreService eventStore,
			OrderRepository orders, PaymentSettlementRepository settlements, PaymentAttemptRepository paymentAttempts,
			CourseRefundContracts.CourseRefundEvidencePort courseEvidence, CourseClaimContracts.CourseClaims courseClaims,
			ObjectProvider<VnPayRefundClient> vnPay, PlatformTransactionManager txManager) {
		this.requests = requests;
		this.allocations = allocations;
		this.providerAttempts = providerAttempts;
		this.auditedRequests = auditedRequests;
		this.adminReview = adminReview;
		this.persistence = persistence;
		this.eventStore = eventStore;
		this.orders = orders;
		this.settlements = settlements;
		this.paymentAttempts = paymentAttempts;
		this.courseEvidence = courseEvidence;
		this.courseClaims = courseClaims;
		this.vnPay = vnPay;
		this.tx = new TransactionTemplate(txManager);
	}

	// =================================================================== học viên

	public record Eligibility(Integer orderId, Integer orderItemId, String itemName, long amountVnd, boolean eligible,
			String reason, Instant deadline, int progressPercent, boolean hasSubmission, Long activeRequestId) {
	}

	/** Học viên xem mình có được hoàn tiền dòng đơn hàng này không (chỉ đọc, không giữ chỗ). */
	public Eligibility eligibility(Integer userId, Integer orderId, Integer orderItemId) {
		return tx.execute(s -> {
			OrderItemJpaEntity item = requireCourseItem(userId, orderId, orderItemId);
			var settlement = settlements.findByOrderId(orderId).orElse(null);
			Long active = activeRequestId(orderItemId);
			if (settlement == null) {
				return new Eligibility(orderId, orderItemId, item.getNameSnapshot(), item.getNetAmountVnd(), false,
						"ORDER_NOT_SETTLED", null, 0, false, active);
			}
			Instant now = Instant.now();
			var evidence = courseEvidence.inspect(new CourseRefundContracts.EvidenceRequest(
					new CourseRefundContracts.RefundScope(userId, orderId, orderItemId), now));
			var course = evidence.orderedCourses().getFirst();
			var result = evidence.evaluateWholeScope(policy, true, settlement.getProviderPaidAt(), now,
					RefundEligibilityPolicy.WindowBoundary.DEADLINE_INCLUSIVE);
			boolean eligible = result.decision() == RefundEligibilityPolicy.Decision.ELIGIBLE && active == null;
			String reason = active != null ? "REQUEST_ALREADY_OPEN" : result.reason().name();
			return new Eligibility(orderId, orderItemId, item.getNameSnapshot(), item.getNetAmountVnd(), eligible,
					reason, settlement.getProviderPaidAt().plus(RefundEligibilityPolicy.COURSE_REFUND_WINDOW),
					course.progressBasisPoints() / 100, course.hasSuccessfulAssignment(), active);
		});
	}

	/** Gửi yêu cầu hoàn tiền. Gửi lại cùng Idempotency-Key và cùng nội dung trả về đúng yêu cầu cũ. */
	public RefundRequestJpaEntity request(Integer userId, Integer orderId, Integer orderItemId, String idempotencyKey,
			String reasonCode, String note) {
		String key = required(idempotencyKey, 128, "Thiếu Idempotency-Key");
		String reason = required(reasonCode, 64, "Vui lòng chọn lý do hoàn tiền");
		String cleanNote = note == null || note.isBlank() ? null : note.strip();
		if (cleanNote != null && cleanNote.length() > 1000) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Ghi chú tối đa 1000 ký tự");
		}
		var replay = requests.findByUserIdAndIdempotencyKey(userId, key);
		if (replay.isEmpty()) {
			Eligibility e = eligibility(userId, orderId, orderItemId);
			if (!e.eligible()) {
				throw new ApiException(HttpStatus.CONFLICT, "Không đủ điều kiện hoàn tiền: " + reasonText(e.reason()));
			}
		}
		String fingerprint = sha("course-refund-request-v1|" + userId + "|" + orderId + "|" + orderItemId + "|" + reason
				+ "|" + (cleanNote == null ? "" : cleanNote));
		try {
			return auditedRequests.create(new RefundPersistenceService.CreateCommand(userId, orderId, orderItemId, key,
					fingerprint, 1, reason, cleanNote, false, Instant.now().truncatedTo(ChronoUnit.MICROS))).request();
		} catch (RefundAuditedRequestService.DurableRequestConflict | RefundPersistenceService.RequestIdempotencyConflict e) {
			throw new ApiException(HttpStatus.CONFLICT, "Idempotency-Key đã dùng cho một yêu cầu khác");
		} catch (IllegalArgumentException | IllegalStateException e) {
			throw new ApiException(HttpStatus.CONFLICT, "Không tạo được yêu cầu hoàn tiền: " + e.getMessage());
		}
	}

	public List<RefundRequestJpaEntity> myRequests(Integer userId) {
		return requests.findAllByUserIdOrderByRequestedAtDescIdDesc(userId);
	}

	// =================================================================== admin

	public List<RefundRequestJpaEntity> allRequests() {
		return requests.findAllByOrderByRequestedAtDescIdDesc();
	}

	public RefundRequestJpaEntity reject(Long requestId, String reasonCode) {
		String reason = required(reasonCode, 64, "Vui lòng nhập lý do từ chối");
		try {
			return adminReview.reject(requestId, "admin-reject-" + requestId, reason).request();
		} catch (RefundAdminReviewService.RefundNotFoundException e) {
			throw new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hoàn tiền");
		} catch (RefundAdminReviewService.RefundDecisionConflictException
				| RefundAdminReviewService.DurableReviewConflictException
				| RefundAdminReviewService.ReviewUnavailableException e) {
			throw new ApiException(HttpStatus.CONFLICT, "Yêu cầu đã được xử lý, không từ chối được nữa");
		}
	}

	/**
	 * Duyệt: kiểm tra lại điều kiện có khóa dòng (C), ghi APPROVED; sau khi commit thì trả tiền qua VNPay (nếu bật).
	 * Không bật VNPay: yêu cầu dừng ở READY, admin xác nhận đã hoàn thủ công bằng {@link #confirmManualRefund}.
	 */
	public RefundRequestJpaEntity approve(Integer adminId, Long requestId) {
		tx.executeWithoutResult(s -> approveInTransaction(adminId, requestId));
		if (vnPay.getIfAvailable() != null) {
			executeProviderRefund(adminId, requestId);
		}
		return requests.findById(requestId).orElseThrow();
	}

	/** Thử trả tiền lại qua VNPay sau một lần thất bại dứt khoát. */
	public RefundRequestJpaEntity retry(Integer adminId, Long requestId) {
		if (vnPay.getIfAvailable() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "Chưa bật VNPay; hãy xác nhận đã hoàn tiền thủ công");
		}
		tx.executeWithoutResult(s -> {
			RefundRequestJpaEntity r = lockForAdmin(requestId);
			if (r.getFinancialStatus() != FinancialStatus.FAILED) {
				throw new ApiException(HttpStatus.CONFLICT, "Chỉ thử lại được yêu cầu hoàn tiền bị thất bại");
			}
			releaseFailedSlots(requestId);
			r.reopenFinancialForRetry(Instant.now());
		});
		executeProviderRefund(adminId, requestId);
		return requests.findById(requestId).orElseThrow();
	}

	/**
	 * Admin đã hoàn tiền cho học viên bằng cách khác (chuyển khoản / VNPay merchant portal): ghi nhận thành công
	 * và thu hồi quyền học. Dùng khi VNPay không hoàn được (ví dụ giao dịch thử nghiệm) hoặc chưa bật VNPay.
	 */
	public RefundRequestJpaEntity confirmManualRefund(Integer adminId, Long requestId, String note) {
		String reference = required(note, 200, "Vui lòng nhập mã / ghi chú chuyển khoản");
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		tx.executeWithoutResult(s -> {
			RefundRequestJpaEntity r = lockForAdmin(requestId);
			if (r.getDecisionStatus() != DecisionStatus.APPROVED) {
				throw new ApiException(HttpStatus.CONFLICT, "Yêu cầu chưa được duyệt");
			}
			if (r.getFinancialStatus() == FinancialStatus.SUCCEEDED) {
				throw new ApiException(HttpStatus.CONFLICT, "Yêu cầu đã được hoàn tiền");
			}
			if (r.getFinancialStatus() == FinancialStatus.FAILED) {
				releaseFailedSlots(requestId);
				r.reopenFinancialForRetry(now);
			}
			if (r.getFinancialStatus() == FinancialStatus.UNKNOWN) {
				throw new ApiException(HttpStatus.CONFLICT,
						"Kết quả VNPay chưa rõ; kiểm tra trên cổng VNPay trước khi xác nhận để tránh hoàn hai lần");
			}
			if (r.getFinancialStatus() == FinancialStatus.PROCESSING && providerAttempts.findActiveSlotForUpdate(requestId)
					.filter(a -> !PROVIDER_MANUAL.equals(a.getProvider())).isPresent()) {
				throw new ApiException(HttpStatus.CONFLICT,
						"Đang chờ VNPay trả kết quả; kiểm tra trên cổng VNPay rồi chốt kết quả trước");
			}
		});
		// Lần xác nhận trước bị lỗi giữa chừng → dùng lại lần chuyển khoản thủ công đang mở thay vì tạo mới.
		Long attemptId = tx.execute(s -> providerAttempts.findActiveSlotForUpdate(requestId)
				.filter(a -> PROVIDER_MANUAL.equals(a.getProvider())
						&& a.getAttemptStatus() == ProviderAttemptStatus.PROCESSING)
				.map(a -> a.getId()).orElse(null));
		if (attemptId == null) {
			var attempt = persistence.prepareProviderAttempt(requestId, ownerOf(requestId), PROVIDER_MANUAL,
					"manual-" + requestId + "-" + UUID.randomUUID(), now);
			persistence.startProviderAttempt(requestId, ownerOf(requestId), attempt.getId(), now);
			attemptId = attempt.getId();
		}
		Long finalAttemptId = attemptId;
		// Mã tham chiếu phải duy nhất → dựng từ id; ghi chú của admin nằm trong bằng chứng.
		tx.executeWithoutResult(s -> recordSuccess(requestId, finalAttemptId,
				"manual:" + requestId + ":" + finalAttemptId,
				sha("manual|" + requestId + "|" + adminId + "|" + reference),
				evidence("ADMIN_MANUAL_TRANSFER", reference), now));
		return requests.findById(requestId).orElseThrow();
	}

	/**
	 * VNPay trả kết quả không rõ (ví dụ mã 99, phản hồi không ký). Admin kiểm tra trên cổng VNPay rồi chốt:
	 * refunded=true → tiền đã hoàn, ghi thành công và thu hồi quyền học; refunded=false → VNPay chưa hoàn,
	 * chuyển sang FAILED để admin thử lại hoặc chuyển khoản thủ công.
	 */
	public RefundRequestJpaEntity resolveUnknown(Integer adminId, Long requestId, boolean refunded, String note) {
		String reference = required(note, 200, "Vui lòng nhập ghi chú kết quả kiểm tra trên cổng VNPay");
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		tx.executeWithoutResult(s -> {
			RefundRequestJpaEntity r = lockForAdmin(requestId);
			// PROCESSING: lời gọi VNPay bị gián đoạn (mất mạng / tắt máy chủ) nên chưa ghi được kết quả.
			var attempt = providerAttempts.findActiveSlotForUpdate(requestId)
					.filter(a -> PROVIDER_VNPAY.equals(a.getProvider()))
					.orElse(null);
			boolean awaiting = r.getFinancialStatus() == FinancialStatus.UNKNOWN
					|| r.getFinancialStatus() == FinancialStatus.PROCESSING;
			if (!awaiting || attempt == null) {
				throw new ApiException(HttpStatus.CONFLICT, "Chỉ chốt được yêu cầu đang chờ xác minh với VNPay");
			}
			String fingerprint = sha("resolve|" + requestId + "|" + adminId + "|" + refunded + "|" + reference);
			if (refunded) {
				recordSuccess(requestId, attempt.getId(), "verified:" + requestId + ":" + attempt.getId(), fingerprint,
						evidence("ADMIN_VERIFIED_PROVIDER_REFUND", reference), now);
				return;
			}
			String before = state(r);
			attempt.recordDefinitiveFailure(fingerprint, evidence("ADMIN_VERIFIED_NOT_REFUNDED", reference), now);
			r.markFinancialFailed(now);
			record(r, attempt.getId(), RefundStateMachine.OperationType.PROVIDER_OUTCOME.name(),
					"provider-" + attempt.getId(), fingerprint, before, "ADMIN_REVIEW",
					evidence("ADMIN_VERIFIED_NOT_REFUNDED", reference), now);
		});
		return requests.findById(requestId).orElseThrow();
	}

	// =================================================================== các bước nội bộ

	private void approveInTransaction(Integer adminId, Long requestId) {
		RefundRequestJpaEntity r = lockForAdmin(requestId);
		if (r.getDecisionStatus() != DecisionStatus.REQUESTED) {
			throw new ApiException(HttpStatus.CONFLICT, "Yêu cầu đã được xử lý");
		}
		if (r.getItemTypeSnapshot() != ItemType.COURSE || r.isPhysicalEffectRequired()) {
			throw new ApiException(HttpStatus.CONFLICT, "Hiện chỉ hỗ trợ hoàn tiền khóa học lẻ");
		}
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var scope = new CourseRefundContracts.RefundScope(r.getUserId(), r.getOrderId(), r.getOrderItemId());
		var first = courseEvidence.inspect(new CourseRefundContracts.EvidenceRequest(scope, now));
		var fenced = courseEvidence.revalidateOrFence(new CourseRefundContracts.RevalidationRequest(first, now));
		fenced.requireDecisionSafe();
		var decision = fenced.evaluateWholeScope(policy, true, r.getProviderPaidAt(), now,
				RefundEligibilityPolicy.WindowBoundary.DEADLINE_INCLUSIVE);
		if (decision.decision() != RefundEligibilityPolicy.Decision.ELIGIBLE) {
			throw new ApiException(HttpStatus.CONFLICT,
					"Học viên không còn đủ điều kiện hoàn tiền: " + reasonText(decision.reason().name())
							+ ". Hãy từ chối yêu cầu.");
		}
		var stateScope = new RefundStateMachine.RefundScope(Math.toIntExact(r.getId()), r.getOrderItemId());
		var domain = RefundStateMachine.requestCourse(stateScope, true, r.getRequestedAmountVnd()).state().orElseThrow();
		String fingerprint = sha("refund-admin-approval-v1|" + requestId + "|" + adminId);
		var transition = RefundStateMachine.approve(domain, new RefundStateMachine.EventIdentity(
				RefundStateMachine.OperationType.ADMIN_APPROVAL, stateScope, "admin-approve-" + requestId, fingerprint),
				new RefundStateMachine.CourseApprovalEvidence(RefundStateMachine.Evidence.CONFIRMED));
		if (transition.outcome() != RefundStateMachine.Outcome.APPLIED) {
			throw new ApiException(HttpStatus.CONFLICT, "Không duyệt được: " + transition.reason());
		}
		String before = state(r);
		var course = fenced.orderedCourses().getFirst();
		r.approve(adminId, now);
		r.confirmCourseEligibility(course.evidenceReference(), String.valueOf(course.evidenceVersion()),
				course.observedAt(), fenced.fenceReference(), now);
		record(r, null, RefundStateMachine.OperationType.ADMIN_APPROVAL.name(), "admin-approve-" + requestId,
				fingerprint, before, "ADMIN_REVIEW", "COURSE_ELIGIBLE_" + course.progressBasisPoints() + "BP", now);
	}

	/** Gọi VNPay ngoài transaction; trước và sau đều ghi trạng thái bền vững để phục hồi được khi lỗi giữa chừng. */
	private void executeProviderRefund(Integer adminId, Long requestId) {
		Integer owner = ownerOf(requestId);
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		String operationKey = "vnp-refund-" + requestId + "-" + UUID.randomUUID().toString().substring(0, 8);
		var attempt = persistence.prepareProviderAttempt(requestId, owner, PROVIDER_VNPAY, operationKey, now);
		persistence.startProviderAttempt(requestId, owner, attempt.getId(), now);
		RefundRequestJpaEntity r = requests.findById(requestId).orElseThrow();
		var settlement = settlements.findByOrderId(r.getOrderId()).orElseThrow();
		var payment = paymentAttempts.findByIdAndUserId(settlement.getPaymentAttemptId(), owner).orElseThrow();
		String requestIdForVnPay = ("R" + requestId + "T" + System.currentTimeMillis()).substring(0,
				Math.min(32, ("R" + requestId + "T" + System.currentTimeMillis()).length()));
		VnPayRefundClient.RefundResult result = vnPay.getObject().refund(new VnPayRefundClient.RefundCommand(
				requestIdForVnPay, payment.getMerchantReference(), payment.getTransactionReference(),
				r.getRequestedAmountVnd(), payment.getCreatedAt(),
				r.getRequestedAmountVnd() == r.getCapturedAmountVndSnapshot(), "admin-" + adminId,
				"Hoan tien don " + r.getOrderId()));
		Instant done = Instant.now().truncatedTo(ChronoUnit.MICROS);
		switch (result.outcome()) {
			case SUCCESS -> tx.executeWithoutResult(s -> recordSuccess(requestId, attempt.getId(),
					result.providerReference(), result.responseFingerprint(), "VNPAY_" + result.responseCode(), done));
			case DEFINITIVE_FAILURE -> tx.executeWithoutResult(s -> {
				RefundRequestJpaEntity locked = lockForAdmin(requestId);
				String before = state(locked);
				providerAttempts.findForUpdate(attempt.getId(), requestId).orElseThrow()
						.recordDefinitiveFailure(result.responseFingerprint(), evidence("VNPAY_" + result.responseCode(), result.message()), done);
				locked.markFinancialFailed(done);
				record(locked, attempt.getId(), RefundStateMachine.OperationType.PROVIDER_OUTCOME.name(),
						"provider-" + attempt.getId(), result.responseFingerprint(), before, "VNPAY",
						evidence("FAILED_" + result.responseCode(), result.message()), done);
			});
			case UNKNOWN -> persistence.recordUnknown(requestId, owner, attempt.getId(), result.responseFingerprint(),
					evidence("VNPAY_UNKNOWN_" + result.responseCode(), result.message()), done);
		}
	}

	/** Tiền đã hoàn → SUCCEEDED; C thu hồi quyền học của đúng OrderItem này trong cùng transaction → REVOKED. */
	private void recordSuccess(Long requestId, Long attemptId, String providerReference, String fingerprint,
			String evidenceCode, Instant at) {
		RefundRequestJpaEntity r = lockForAdmin(requestId);
		String before = state(r);
		providerAttempts.findForUpdate(attemptId, requestId).orElseThrow().succeed(providerReference, fingerprint,
				evidenceCode, at);
		r.markFinancialSucceeded(at);
		allocations.findAllForRequestUpdate(requestId).forEach(a -> a.markRefunded());
		record(r, attemptId, RefundStateMachine.OperationType.PROVIDER_OUTCOME.name(), "provider-" + attemptId,
				fingerprint, before, "PROVIDER", evidenceCode, at);
		String beforeRevoke = state(r);
		courseClaims.refundOrderItem(new CourseClaimContracts.RefundOrderItemCommand(r.getUserId(), r.getOrderId(),
				r.getOrderItemId(), "refund-" + requestId, at));
		r.markCourseAccessRevoked(at);
		record(r, null, RefundStateMachine.OperationType.COURSE_ACCESS_REVOCATION.name(), "revoke-" + requestId,
				sha("revoke|" + requestId + "|" + attemptId), beforeRevoke, "MEMBER_C", "ENROLLMENT_REVOKED", at);
	}

	private void releaseFailedSlots(Long requestId) {
		providerAttempts.findActiveSlotForUpdate(requestId).ifPresent(a -> {
			if (a.getAttemptStatus() == ProviderAttemptStatus.DEFINITIVE_FAILURE) {
				a.authorizeRetry(Instant.now());
			}
		});
	}

	private void record(RefundRequestJpaEntity r, Long attemptId, String operation, String eventKey, String fingerprint,
			String before, String source, String evidence, Instant at) {
		String fp = fingerprint.matches("[0-9a-f]{64}") ? fingerprint : sha(fingerprint);
		eventStore.record(new RefundEventStoreService.EventCommand(r.getId(), r.getUserId(), attemptId, operation,
				eventKey, fp, before, state(r), new RefundEventJpaEntity.EvidenceSnapshot(source, evidence, null, fp, at),
				at, at));
	}

	private RefundRequestJpaEntity lockForAdmin(Long requestId) {
		return requests.findForAdminUpdate(requestId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hoàn tiền"));
	}

	private Integer ownerOf(Long requestId) {
		return requests.findById(requestId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hoàn tiền"))
				.getUserId();
	}

	private OrderItemJpaEntity requireCourseItem(Integer userId, Integer orderId, Integer orderItemId) {
		var order = orders.findByIdAndUserId(orderId, userId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng"));
		OrderItemJpaEntity item = order.getItems().stream().filter(i -> i.getId().equals(orderItemId)).findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy dòng đơn hàng"));
		if (item.getItemType() != OrderItemType.COURSE) {
			throw new ApiException(HttpStatus.CONFLICT, "Hiện chỉ hỗ trợ hoàn tiền khóa học lẻ");
		}
		return item;
	}

	private Long activeRequestId(Integer orderItemId) {
		return requests.findAllByOrderItemIdOrderByIdDesc(orderItemId).stream()
				.filter(r -> r.getAmountReservationStatus() != AmountReservationStatus.RELEASED)
				.map(RefundRequestJpaEntity::getId).findFirst().orElse(null);
	}

	private static String state(RefundRequestJpaEntity r) {
		return sha(r.getDecisionStatus() + "|" + r.getFinancialStatus() + "|" + r.getAmountReservationStatus() + "|"
				+ r.getCourseAccessStatus());
	}

	private static String evidence(String code, String message) {
		String m = (code + ":" + (message == null ? "" : message)).replaceAll("\\s+", " ").strip();
		return m.length() <= 255 ? m : m.substring(0, 255);
	}

	private static String required(String value, int max, String message) {
		String v = value == null ? "" : value.strip();
		if (v.isEmpty() || v.length() > max) {
			throw new ApiException(HttpStatus.BAD_REQUEST, message);
		}
		return v;
	}

	static String reasonText(String reason) {
		return switch (Objects.requireNonNullElse(reason, "")) {
			case "COURSE_PROGRESS_OVER_LIMIT" -> "đã học quá 20% khóa học";
			case "COURSE_ASSIGNMENT_SUBMITTED" -> "đã nộp bài tập";
			case "COURSE_WINDOW_EXPIRED" -> "đã quá 7 ngày kể từ khi thanh toán";
			case "ORDER_NOT_SETTLED" -> "đơn hàng chưa thanh toán xong";
			case "REQUEST_ALREADY_OPEN" -> "đã có yêu cầu hoàn tiền cho khóa này";
			case "COURSE_EVIDENCE_MISSING", "COURSE_EVIDENCE_INVALID" -> "chưa kiểm tra được tiến độ học";
			default -> reason;
		};
	}

	static String sha(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	Optional<VnPayRefundClient> vnPayClient() {
		return Optional.ofNullable(vnPay.getIfAvailable());
	}
}
