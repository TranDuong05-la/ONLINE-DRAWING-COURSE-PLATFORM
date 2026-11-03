package com.drawingcourse.backend.integration.contract;

import com.drawingcourse.backend.entity.RefundEligibilityPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Pure Member C boundary. It defines evidence shapes and no enrollment/claim persistence. */
public final class CourseRefundContracts {
    private CourseRefundContracts() { }

    public interface CourseRefundEvidencePort {
        CourseRefundEvidence inspect(EvidenceRequest request);

        CourseRefundEvidence revalidateOrFence(RevalidationRequest request);
    }

    public enum SourceType { STANDALONE_COURSE, WHOLE_COMBO }

    public enum ConsistencyGuarantee {
        REVALIDATED_AT_OBSERVATION,
        FENCED_FOR_REFUND_DECISION,
        UNSUPPORTED
    }

    public record RefundScope(Integer authenticatedUserId, Integer orderId, Integer orderItemId) {
        public RefundScope {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            requirePositive(orderId, "orderId");
            requirePositive(orderItemId, "orderItemId");
        }
    }

    public record EvidenceRequest(RefundScope scope, Instant observedAt) {
        public EvidenceRequest {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public record CourseEvidence(Long courseId, Integer componentPosition, int progressBasisPoints,
                                 boolean hasSuccessfulAssignment, Instant observedAt,
                                 String evidenceReference, long evidenceVersion) {
        public CourseEvidence {
            requirePositive(courseId, "courseId");
            if (componentPosition != null && componentPosition < 0)
                throw new IllegalArgumentException("componentPosition must be nonnegative");
            if (progressBasisPoints < 0 || progressBasisPoints > 10_000)
                throw new IllegalArgumentException("progressBasisPoints must be in [0,10000]");
            Objects.requireNonNull(observedAt, "observedAt");
            evidenceReference = requireReference(evidenceReference, "evidenceReference");
            if (evidenceVersion < 0) throw new IllegalArgumentException("evidenceVersion must be nonnegative");
        }

        public RefundEligibilityPolicy.CourseEvidence toPolicyEvidence() {
            return new RefundEligibilityPolicy.CourseEvidence(progressBasisPoints, hasSuccessfulAssignment);
        }
    }

    /** Ordered, complete evidence for exactly one standalone Course or one whole Combo. */
    public record CourseRefundEvidence(RefundScope scope, SourceType sourceType,
                                       List<CourseEvidence> orderedCourses,
                                       ConsistencyGuarantee consistencyGuarantee,
                                       String fenceReference) {
        public CourseRefundEvidence {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(sourceType, "sourceType");
            orderedCourses = immutableCompleteCourses(sourceType, orderedCourses);
            Objects.requireNonNull(consistencyGuarantee, "consistencyGuarantee");
            if (consistencyGuarantee == ConsistencyGuarantee.FENCED_FOR_REFUND_DECISION)
                fenceReference = requireReference(fenceReference, "fenceReference");
            else if (fenceReference != null)
                throw new IllegalArgumentException("Only fenced evidence may carry a fenceReference");
        }

        public void requireDecisionSafe() {
            if (consistencyGuarantee == ConsistencyGuarantee.UNSUPPORTED)
                throw new IllegalStateException("Course refund evidence has no safe consistency guarantee");
        }

        public RefundEligibilityPolicy.ComboResult evaluateWholeScope(RefundEligibilityPolicy policy,
                                                                       boolean committedSettlement,
                                                                       Instant windowStart,
                                                                       Instant evaluatedAt,
                                                                       RefundEligibilityPolicy.WindowBoundary boundary) {
            Objects.requireNonNull(policy, "policy");
            return policy.evaluateCombo(committedSettlement,
                    orderedCourses.stream().map(CourseEvidence::toPolicyEvidence).toList(),
                    windowStart, evaluatedAt, boundary);
        }
    }

    public record RevalidationRequest(CourseRefundEvidence priorEvidence, Instant observedAt) {
        public RevalidationRequest {
            Objects.requireNonNull(priorEvidence, "priorEvidence");
            Objects.requireNonNull(observedAt, "observedAt");
            if (observedAt.isBefore(priorEvidence.orderedCourses().getFirst().observedAt()))
                throw new IllegalArgumentException("revalidation cannot precede prior evidence");
        }
    }

    private static List<CourseEvidence> immutableCompleteCourses(SourceType sourceType,
                                                                  List<CourseEvidence> source) {
        Objects.requireNonNull(source, "orderedCourses");
        if (source.isEmpty()) throw new IllegalArgumentException("Course evidence cannot be empty");
        var copy = new ArrayList<CourseEvidence>(source.size());
        var ids = new HashSet<Long>();
        Instant observedAt = null;
        for (int index = 0; index < source.size(); index++) {
            CourseEvidence evidence = Objects.requireNonNull(source.get(index), "Course evidence");
            if (!ids.add(evidence.courseId())) throw new IllegalArgumentException("duplicate Course evidence");
            if (observedAt == null) observedAt = evidence.observedAt();
            else if (!observedAt.equals(evidence.observedAt()))
                throw new IllegalArgumentException("Course evidence must use one observation instant");
            if (sourceType == SourceType.STANDALONE_COURSE && evidence.componentPosition() != null)
                throw new IllegalArgumentException("standalone Course cannot have a component position");
            if (sourceType == SourceType.WHOLE_COMBO
                    && !Integer.valueOf(index).equals(evidence.componentPosition()))
                throw new IllegalArgumentException("Combo evidence positions must be contiguous and ordered");
            copy.add(evidence);
        }
        if (sourceType == SourceType.STANDALONE_COURSE && copy.size() != 1)
            throw new IllegalArgumentException("standalone Course evidence must contain exactly one Course");
        return List.copyOf(copy);
    }

    private static String requireReference(String value, String name) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty() || result.length() > 128)
            throw new IllegalArgumentException(name + " must contain 1..128 characters");
        return result;
    }

    private static void requirePositive(Integer value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
