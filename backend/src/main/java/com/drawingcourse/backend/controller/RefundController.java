package com.drawingcourse.backend.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.artacademy.common.ApiException;
import com.artacademy.dto.ApiResponse;
import com.artacademy.entity.User;
import com.artacademy.repository.UserRepository;
import com.artacademy.security.AuthenticatedUserIdResolver;
import com.drawingcourse.backend.entity.OrderItemJpaEntity;
import com.drawingcourse.backend.entity.RefundRequestJpaEntity;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.service.refund.CourseRefundWorkflowService;

/**
 * Hoàn tiền khóa học: học viên (/api/refunds) và admin (/api/admin/refunds, chỉ ADMIN theo SecurityConfig).
 * userId luôn lấy từ token; mọi quy tắc nằm trong {@link CourseRefundWorkflowService}.
 */
@RestController
public class RefundController {
	private final CourseRefundWorkflowService workflow;
	private final AuthenticatedUserIdResolver currentUser;
	private final OrderRepository orders;
	private final UserRepository users;
	private final org.springframework.transaction.support.TransactionTemplate readTx;

	public RefundController(CourseRefundWorkflowService workflow, AuthenticatedUserIdResolver currentUser,
			OrderRepository orders, UserRepository users,
			org.springframework.transaction.PlatformTransactionManager txManager) {
		this.workflow = workflow;
		this.currentUser = currentUser;
		this.orders = orders;
		this.users = users;
		this.readTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
		this.readTx.setReadOnly(true);
	}

	public record RefundDto(Long id, Integer orderId, Integer orderItemId, String itemName, long amountVnd,
			String reasonCode, String note, String status, String statusLabel, String decisionStatus,
			String financialStatus, String courseAccessStatus, Instant requestedAt, Instant decisionAt,
			Instant refundedAt, Integer userId, String learnerName, String learnerEmail) {
	}

	public record CreateRefundRequest(Integer orderId, Integer orderItemId, String reasonCode, String note) {
	}

	public record RejectRequest(String reason) {
	}

	public record ManualRefundRequest(String note) {
	}

	// ---------------------------------------------------------------- học viên

	@GetMapping("/api/refunds")
	public ApiResponse<List<RefundDto>> mine() {
		Integer userId = currentUser.requireUserId();
		return ApiResponse.success("OK", toDtos(workflow.myRequests(userId), false));
	}

	@GetMapping("/api/refunds/eligibility")
	public ApiResponse<CourseRefundWorkflowService.Eligibility> eligibility(@RequestParam Integer orderId,
			@RequestParam Integer orderItemId) {
		return ApiResponse.success("OK", workflow.eligibility(currentUser.requireUserId(), orderId, orderItemId));
	}

	@PostMapping("/api/refunds")
	public ResponseEntity<ApiResponse<RefundDto>> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestBody CreateRefundRequest body) {
		Integer userId = currentUser.requireUserId();
		RefundRequestJpaEntity r = workflow.request(userId, body.orderId(), body.orderItemId(), idempotencyKey,
				body.reasonCode(), body.note());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success("Đã gửi yêu cầu hoàn tiền", toDtos(List.of(r), false).getFirst()));
	}

	// ---------------------------------------------------------------- admin

	@GetMapping("/api/admin/refunds")
	public ApiResponse<List<RefundDto>> all() {
		return ApiResponse.success("OK", toDtos(workflow.allRequests(), true));
	}

	@PostMapping("/api/admin/refunds/{id}/approve")
	public ApiResponse<RefundDto> approve(@PathVariable Long id) {
		RefundRequestJpaEntity r = workflow.approve(currentUser.requireUserId(), id);
		return ApiResponse.success(message(r), toDtos(List.of(r), true).getFirst());
	}

	@PostMapping("/api/admin/refunds/{id}/reject")
	public ApiResponse<RefundDto> reject(@PathVariable Long id, @RequestBody RejectRequest body) {
		RefundRequestJpaEntity r = workflow.reject(id, body == null ? null : body.reason());
		return ApiResponse.success("Đã từ chối yêu cầu hoàn tiền", toDtos(List.of(r), true).getFirst());
	}

	@PostMapping("/api/admin/refunds/{id}/retry")
	public ApiResponse<RefundDto> retry(@PathVariable Long id) {
		RefundRequestJpaEntity r = workflow.retry(currentUser.requireUserId(), id);
		return ApiResponse.success(message(r), toDtos(List.of(r), true).getFirst());
	}

	@PostMapping("/api/admin/refunds/{id}/confirm-manual")
	public ApiResponse<RefundDto> confirmManual(@PathVariable Long id, @RequestBody ManualRefundRequest body) {
		RefundRequestJpaEntity r = workflow.confirmManualRefund(currentUser.requireUserId(), id,
				body == null ? null : body.note());
		return ApiResponse.success("Đã ghi nhận hoàn tiền thủ công và thu hồi quyền học", toDtos(List.of(r), true).getFirst());
	}

	/** Chốt yêu cầu "Đang xác minh với VNPay" sau khi admin kiểm tra trên cổng VNPay. */
	@PostMapping("/api/admin/refunds/{id}/resolve")
	public ApiResponse<RefundDto> resolve(@PathVariable Long id, @RequestBody ResolveRequest body) {
		if (body == null || body.refunded() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Vui lòng chọn VNPay đã hoàn tiền hay chưa");
		}
		RefundRequestJpaEntity r = workflow.resolveUnknown(currentUser.requireUserId(), id, body.refunded(), body.note());
		return ApiResponse.success(body.refunded() ? "Đã xác nhận VNPay hoàn tiền và thu hồi quyền học"
				: "Đã đánh dấu VNPay chưa hoàn tiền; có thể thử lại hoặc chuyển khoản thủ công",
				toDtos(List.of(r), true).getFirst());
	}

	public record ResolveRequest(Boolean refunded, String note) {
	}

	// ---------------------------------------------------------------- mapping

	private List<RefundDto> toDtos(List<RefundRequestJpaEntity> list, boolean withLearner) {
		// Tên khóa nằm trong order.items (nạp lười): đọc trong transaction chỉ đọc.
		return readTx.execute(s -> mapDtos(list, withLearner));
	}

	private List<RefundDto> mapDtos(List<RefundRequestJpaEntity> list, boolean withLearner) {
		Map<Integer, User> learners = withLearner
				? users.findAllById(list.stream().map(RefundRequestJpaEntity::getUserId).distinct().toList()).stream()
						.collect(Collectors.toMap(User::getId, Function.identity()))
				: Map.of();
		return list.stream().map(r -> {
			String itemName = orders.findByIdAndUserId(r.getOrderId(), r.getUserId())
					.flatMap(o -> o.getItems().stream().filter(i -> i.getId().equals(r.getOrderItemId())).findFirst())
					.map(OrderItemJpaEntity::getNameSnapshot).orElse("");
			User u = learners.get(r.getUserId());
			String status = status(r);
			return new RefundDto(r.getId(), r.getOrderId(), r.getOrderItemId(), itemName, r.getRequestedAmountVnd(),
					r.getReasonCode(), r.getCustomerNote(), status, label(status), r.getDecisionStatus().name(),
					r.getFinancialStatus().name(), r.getCourseAccessStatus().name(), r.getRequestedAt(),
					r.getDecisionAt(), r.getFinancialExecutedAt(), r.getUserId(), u == null ? null : u.getFullName(),
					u == null ? null : u.getEmail());
		}).toList();
	}

	/** Trạng thái gộp cho app: PENDING / REJECTED / PROCESSING / VERIFYING / FAILED / REFUNDED. */
	static String status(RefundRequestJpaEntity r) {
		return switch (r.getDecisionStatus()) {
			case REQUESTED -> "PENDING";
			case REJECTED -> "REJECTED";
			case APPROVED -> switch (r.getFinancialStatus()) {
				case SUCCEEDED -> "REFUNDED";
				case FAILED -> "FAILED";
				case UNKNOWN, REQUIRES_REVIEW -> "VERIFYING";
				default -> "PROCESSING";
			};
		};
	}

	static String label(String status) {
		return switch (status) {
			case "PENDING" -> "Chờ duyệt";
			case "REJECTED" -> "Bị từ chối";
			case "REFUNDED" -> "Đã hoàn tiền";
			case "FAILED" -> "Hoàn tiền thất bại";
			case "VERIFYING" -> "Đang xác minh với VNPay";
			default -> "Đang hoàn tiền";
		};
	}

	private static String message(RefundRequestJpaEntity r) {
		return switch (status(r)) {
			case "REFUNDED" -> "Đã hoàn tiền và thu hồi quyền học";
			case "FAILED" -> "VNPay chưa hoàn được tiền; có thể thử lại hoặc xác nhận đã hoàn thủ công";
			case "VERIFYING" -> "Chưa rõ kết quả từ VNPay; cần kiểm tra trên cổng VNPay";
			default -> "Đã duyệt, đang chờ hoàn tiền";
		};
	}
}
