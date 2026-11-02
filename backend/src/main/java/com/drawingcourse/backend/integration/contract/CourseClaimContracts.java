package com.drawingcourse.backend.integration.contract;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Pure C-owned contract shapes. This package provides no claim/enrollment persistence implementation. */
public final class CourseClaimContracts {
    private CourseClaimContracts() { }

    /** Production mutations must join the caller's transaction and enforce database uniqueness. */
    public interface CourseClaims {
        EligibilitySnapshot inspectEligibility(Integer authenticatedUserId, Set<Long> candidateCourseIds,
                                               Instant observedAt);

        void reserve(ReservationCommand command);

        void confirm(ConfirmationCommand command);

        void release(TerminalReleaseCommand command);

        void refundOrderItem(RefundOrderItemCommand command);
    }

    public enum ClaimSourceType { STANDALONE_COURSE, COMBO_COMPONENT }

    public enum TerminalUnpaidReason { EXPIRED, CANCELLED, FINAL_PAYMENT_FAILURE }

    public record ClaimLine(Integer orderItemId, Long courseId, ClaimSourceType sourceType,
                            Integer componentPosition) {
        public ClaimLine {
            requirePositive(orderItemId, "orderItemId");
            requirePositive(courseId, "courseId");
            Objects.requireNonNull(sourceType, "sourceType");
            if (sourceType == ClaimSourceType.STANDALONE_COURSE && componentPosition != null)
                throw new IllegalArgumentException("standalone Course cannot have a component position");
            if (sourceType == ClaimSourceType.COMBO_COMPONENT
                    && (componentPosition == null || componentPosition < 0))
                throw new IllegalArgumentException("Combo component requires a nonnegative position");
        }
    }

    /** Authoritative C result, constrained to the exact requested Course set. */
    public record EligibilitySnapshot(Set<Long> requestedCourseIds, Set<Long> ownedCourseIds,
                                      Set<Long> activePendingCourseIds) {
        public EligibilitySnapshot {
            requestedCourseIds = immutableSortedIds(requestedCourseIds, "requestedCourseIds");
            ownedCourseIds = immutableSortedIds(ownedCourseIds, "ownedCourseIds");
            activePendingCourseIds = immutableSortedIds(activePendingCourseIds, "activePendingCourseIds");
            if (!requestedCourseIds.containsAll(ownedCourseIds)
                    || !requestedCourseIds.containsAll(activePendingCourseIds))
                throw new IllegalArgumentException("eligibility result contains an unrequested Course");
        }

        public boolean rejectsCheckout() {
            return !ownedCourseIds.isEmpty() || !activePendingCourseIds.isEmpty();
        }
    }

    public record ReservationCommand(Integer authenticatedUserId, Integer orderId,
                                     List<ClaimLine> lines, Instant observedAt, Instant expiresAt) {
        public ReservationCommand {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            requirePositive(orderId, "orderId");
            lines = immutableUniqueLines(lines);
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!observedAt.isBefore(expiresAt))
                throw new IllegalArgumentException("claim expiration must be after observation");
        }

        public List<Long> courseIdsInLockOrder() { return sortedCourseIds(lines); }
    }

    public record ConfirmationCommand(Integer authenticatedUserId, Integer orderId,
                                      List<ClaimLine> lines, Instant confirmedAt) {
        public ConfirmationCommand {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            requirePositive(orderId, "orderId");
            lines = immutableUniqueLines(lines);
            Objects.requireNonNull(confirmedAt, "confirmedAt");
        }

        public List<Long> courseIdsInLockOrder() { return sortedCourseIds(lines); }
    }

    public record TerminalReleaseCommand(Integer authenticatedUserId, Integer orderId,
                                         List<ClaimLine> lines, TerminalUnpaidReason reason,
                                         Instant releasedAt) {
        public TerminalReleaseCommand {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            requirePositive(orderId, "orderId");
            lines = immutableUniqueLines(lines);
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(releasedAt, "releasedAt");
        }

        public List<Long> courseIdsInLockOrder() { return sortedCourseIds(lines); }
    }

    /** C must load and atomically revoke the complete persisted claim set for this OrderItem. */
    public record RefundOrderItemCommand(Integer authenticatedUserId, Integer orderId, Integer orderItemId,
                                         String refundOperationId, Instant refundCompletedAt) {
        public RefundOrderItemCommand {
            requirePositive(authenticatedUserId, "authenticatedUserId");
            requirePositive(orderId, "orderId");
            requirePositive(orderItemId, "orderItemId");
            refundOperationId = requireOperationId(refundOperationId);
            Objects.requireNonNull(refundCompletedAt, "refundCompletedAt");
        }
    }

    private static List<ClaimLine> immutableUniqueLines(List<ClaimLine> source) {
        Objects.requireNonNull(source, "lines");
        if (source.isEmpty()) throw new IllegalArgumentException("claim lines must not be empty");
        var copy = new ArrayList<ClaimLine>(source.size());
        var courseIds = new HashSet<Long>();
        for (ClaimLine line : source) {
            ClaimLine required = Objects.requireNonNull(line, "claim line");
            if (!courseIds.add(required.courseId()))
                throw new IllegalArgumentException("duplicate Course claim " + required.courseId());
            copy.add(required);
        }
        return List.copyOf(copy);
    }

    private static Set<Long> immutableSortedIds(Collection<Long> source, String name) {
        Objects.requireNonNull(source, name);
        var sorted = new TreeSet<Long>();
        for (Long id : source) {
            requirePositive(id, "courseId");
            if (!sorted.add(id)) throw new IllegalArgumentException("duplicate Course " + id);
        }
        return Collections.unmodifiableSet(sorted);
    }

    private static List<Long> sortedCourseIds(List<ClaimLine> lines) {
        return lines.stream().map(ClaimLine::courseId).sorted().toList();
    }

    private static void requirePositive(Integer value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static String requireOperationId(String value) {
        String result = Objects.requireNonNull(value, "refundOperationId").strip();
        if (result.isEmpty() || result.length() > 128)
            throw new IllegalArgumentException("refundOperationId must contain 1..128 characters");
        return result;
    }
}
