package com.artacademy.learning.claim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.learning.Assignment;
import com.artacademy.learning.AssignmentRepository;
import com.artacademy.learning.CompletionService;
import com.artacademy.learning.Enrollment;
import com.artacademy.learning.EnrollmentRepository;
import com.artacademy.learning.SubmissionRepository;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.drawingcourse.backend.integration.contract.CourseRefundContracts;
import com.drawingcourse.backend.integration.contract.CourseRefundContracts.CourseEvidence;
import com.drawingcourse.backend.integration.contract.CourseRefundContracts.CourseRefundEvidence;

/**
 * Số liệu học tập để D xét điều kiện hoàn tiền (D giữ chính sách: trong 7 ngày, tiến độ không quá 20%,
 * chưa có bài tập nộp thành công; chưa có chứng nhận thì tiến độ chắc chắn dưới 100%).
 *
 * <ul>
 *   <li>{@code progressBasisPoints}: tiến độ theo quy tắc của {@link CompletionService}, đơn vị 1/10000, KHÔNG giới hạn 99%.</li>
 *   <li>{@code hasSuccessfulAssignment}: học viên đã có ít nhất một bài nộp được lưu cho một đề của khóa
 *       (đang chờ chấm, đạt hay cần sửa đều tính), kể cả đề không bắt buộc.</li>
 * </ul>
 * {@link #inspect} chỉ đọc. {@link #revalidateOrFence} chạy trong transaction của D và khóa các dòng enrollment,
 * nên học viên không thể học thêm/nộp thêm làm thay đổi kết quả cho tới khi D commit quyết định hoàn tiền.
 */
@Service
public class CourseRefundEvidenceService implements CourseRefundContracts.CourseRefundEvidencePort {

	private final CourseClaimRepository claims;
	private final EnrollmentRepository enrollments;
	private final CompletionService completion;
	private final CourseLessonRepository lessons;
	private final AssignmentRepository assignments;
	private final SubmissionRepository submissions;

	public CourseRefundEvidenceService(CourseClaimRepository claims, EnrollmentRepository enrollments,
			CompletionService completion, CourseLessonRepository lessons, AssignmentRepository assignments,
			SubmissionRepository submissions) {
		this.claims = claims;
		this.enrollments = enrollments;
		this.completion = completion;
		this.lessons = lessons;
		this.assignments = assignments;
		this.submissions = submissions;
	}

	@Override
	@Transactional(readOnly = true)
	public CourseRefundEvidence inspect(CourseRefundContracts.EvidenceRequest request) {
		return build(request.scope(), request.observedAt(), false);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public CourseRefundEvidence revalidateOrFence(CourseRefundContracts.RevalidationRequest request) {
		return build(request.priorEvidence().scope(), request.observedAt(), true);
	}

	private CourseRefundEvidence build(CourseRefundContracts.RefundScope scope, Instant observedAt, boolean fence) {
		List<CourseClaim> items = fence ? claims.findByOrderItemForUpdate(scope.orderItemId())
				: claims.findByOrderItemIdOrderByCourseIdAsc(scope.orderItemId());
		if (items.isEmpty()) {
			throw new IllegalStateException("Không có claim cho OrderItem " + scope.orderItemId());
		}
		for (CourseClaim c : items) {
			if (!c.getUserId().equals(scope.authenticatedUserId()) || !c.getOrderId().equals(scope.orderId())) {
				throw new SecurityException("Refund scope does not match the persisted Course claims");
			}
		}
		boolean combo = items.stream().anyMatch(c -> c.getSourceType() == CourseClaim.SourceType.COMBO_COMPONENT);
		List<CourseClaim> ordered = items.stream()
				.sorted(Comparator.comparing((CourseClaim c) -> c.getComponentPosition() == null ? 0 : c.getComponentPosition())
						.thenComparing(CourseClaim::getCourseId))
				.toList();
		List<CourseEvidence> evidence = new ArrayList<>();
		for (CourseClaim c : ordered) {
			Enrollment e = fence ? enrollments.findForUpdate(c.getLearnerProfileId(), c.getCourseId()).orElse(null)
					: enrollments.findByLearnerProfileIdAndCourseId(c.getLearnerProfileId(), c.getCourseId()).orElse(null);
			int basisPoints = Math.min(10_000,
					completion.compute(c.getLearnerProfileId(), c.getCourseId()).basisPoints());
			evidence.add(new CourseEvidence(c.getCourseId().longValue(), combo ? c.getComponentPosition() : null, basisPoints,
					hasAnySubmission(c.getLearnerProfileId(), c.getCourseId()), observedAt, "course-claim:" + c.getId(),
					e == null ? 0 : e.getVersion()));
		}
		return new CourseRefundEvidence(scope,
				combo ? CourseRefundContracts.SourceType.WHOLE_COMBO : CourseRefundContracts.SourceType.STANDALONE_COURSE,
				evidence,
				fence ? CourseRefundContracts.ConsistencyGuarantee.FENCED_FOR_REFUND_DECISION
						: CourseRefundContracts.ConsistencyGuarantee.REVALIDATED_AT_OBSERVATION,
				fence ? "enrollment-lock:order-item:" + scope.orderItemId() : null);
	}

	private boolean hasAnySubmission(Integer learnerProfileId, Integer courseId) {
		List<Integer> lessonIds = lessons.findAllByCourse(courseId).stream().map(CourseLesson::getId).toList();
		if (lessonIds.isEmpty()) {
			return false;
		}
		Map<Long, Assignment> byId = assignments.findByLessonIdIn(lessonIds).stream()
				.collect(Collectors.toMap(Assignment::getId, Function.identity()));
		return !byId.isEmpty()
				&& !submissions.findByLearnerProfileIdAndAssignmentIdIn(learnerProfileId, byId.keySet()).isEmpty();
	}
}
