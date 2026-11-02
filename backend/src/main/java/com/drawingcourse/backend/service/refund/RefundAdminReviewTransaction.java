package com.drawingcourse.backend.service.refund;

import com.drawingcourse.backend.entity.RefundEventJpaEntity;
import com.drawingcourse.backend.entity.RefundRequestJpaEntity;
import com.drawingcourse.backend.entity.RefundStateMachine;
import com.drawingcourse.backend.repository.RefundEventRepository;
import com.drawingcourse.backend.repository.RefundRequestRepository;
import com.drawingcourse.backend.repository.RefundUnitAllocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

import static com.drawingcourse.backend.entity.RefundPersistenceTypes.*;

/** Database transaction for one review decision. Must be called through its Spring proxy. */
@Service
public class RefundAdminReviewTransaction {
    private static final String REJECTION = RefundStateMachine.OperationType.ADMIN_REJECTION.name();
    private final RefundRequestRepository requests;
    private final RefundUnitAllocationRepository allocations;
    private final RefundEventRepository events;
    private final RefundEventStoreService eventStore;

    public RefundAdminReviewTransaction(RefundRequestRepository requests, RefundUnitAllocationRepository allocations,
                                       RefundEventRepository events, RefundEventStoreService eventStore) {
        this.requests = requests;
        this.allocations = allocations;
        this.events = events;
        this.eventStore = eventStore;
    }

    @Transactional
    public RefundAdminReviewService.ReviewResult reject(Long requestId, Integer adminId, String eventKey,
                                                        String reasonCode, String fingerprint, Instant now) {
        RefundRequestJpaEntity request = requests.findForAdminUpdate(requestId)
                .orElseThrow(() -> new RefundAdminReviewService.RefundNotFoundException(requestId));
        var prior = events.findByRefundRequestIdAndOperationTypeAndEventKey(requestId, REJECTION, eventKey);
        if (prior.isPresent()) {
            if (!prior.get().getEventFingerprint().equals(fingerprint))
                throw conflict(request, prior.get(), adminId, eventKey, fingerprint, reasonCode, now);
            if (request.getDecisionStatus() != DecisionStatus.REJECTED
                    || !Objects.equals(request.getDecidedByUserId(), adminId))
                throw new IllegalStateException("Persisted review event and request decision disagree");
            return new RefundAdminReviewService.ReviewResult(request, prior.get(), true);
        }

        if (request.getDecisionStatus() != DecisionStatus.REQUESTED) {
            var priorDecision = events.findAllByRefundRequestIdOrderById(requestId).stream()
                    .filter(event -> event.getOperationType().equals(REJECTION)
                            || event.getOperationType().equals(RefundStateMachine.OperationType.ADMIN_APPROVAL.name()))
                    .reduce((first, second) -> second).orElse(null);
            if (priorDecision != null)
                throw conflict(request, priorDecision, adminId, eventKey, fingerprint, reasonCode, now);
            throw new RefundAdminReviewService.RefundDecisionConflictException(
                    "Refund request already has a terminal Admin decision");
        }
        if (request.getItemTypeSnapshot() != ItemType.COURSE)
            throw new RefundAdminReviewService.ReviewUnavailableException(
                    "This Refund item type requires an unavailable Member C/E review adapter");
        if (request.isPhysicalEffectRequired())
            throw new RefundAdminReviewService.ReviewUnavailableException(
                    "Product rejection requires authoritative Shipment HOLD release");
        if (request.getFinancialStatus() != FinancialStatus.NOT_STARTED
                || request.getAmountReservationStatus() != AmountReservationStatus.RESERVED)
            throw new RefundAdminReviewService.ReviewUnavailableException(
                    "Refund has entered financial processing and cannot be rejected here");

        var reservedUnits = allocations.findAllForRequestUpdate(requestId);
        for (var allocation : reservedUnits) {
            if (allocation.getAllocationStatus() != UnitAllocationStatus.RESERVED)
                throw new IllegalStateException("Refund allocation state disagrees with a pending request");
        }
        if (reservedUnits.isEmpty()) throw new IllegalStateException("Pending refund has no persisted unit allocation");

        var domain = RefundStateMachine.requestCourse(
                new RefundStateMachine.RefundScope(Math.toIntExact(request.getId()), request.getOrderItemId()),
                true, request.getRequestedAmountVnd()).state().orElseThrow();
        var identity = new RefundStateMachine.EventIdentity(RefundStateMachine.OperationType.ADMIN_REJECTION,
                new RefundStateMachine.RefundScope(Math.toIntExact(request.getId()), request.getOrderItemId()),
                eventKey, fingerprint);
        var transition = RefundStateMachine.reject(domain, identity, null);
        if (transition.outcome() != RefundStateMachine.Outcome.APPLIED)
            throw new RefundAdminReviewService.RefundDecisionConflictException(
                    "Refund state machine rejected the Admin decision: " + transition.reason());

        String before = RefundAdminReviewService.stateFingerprint(DecisionStatus.REQUESTED,
                FinancialStatus.NOT_STARTED, AmountReservationStatus.RESERVED, UnitAllocationStatus.RESERVED);
        String after = RefundAdminReviewService.stateFingerprint(DecisionStatus.REJECTED,
                FinancialStatus.NOT_STARTED, AmountReservationStatus.RELEASED, UnitAllocationStatus.RELEASED);
        request.reject(adminId, now);
        reservedUnits.forEach(allocation -> allocation.release(now));
        var evidence = new RefundEventJpaEntity.EvidenceSnapshot("ADMIN_REVIEW", reasonCode, null, fingerprint, now);
        var recorded = eventStore.record(new RefundEventStoreService.EventCommand(requestId, request.getUserId(),
                null, REJECTION, eventKey, fingerprint, before, after, evidence, now, now));
        if (recorded.idempotentReplay())
            throw new IllegalStateException("Review event appeared concurrently despite the request lock");
        return new RefundAdminReviewService.ReviewResult(request, recorded.event(), false);
    }

    private static ReviewEventConflict conflict(RefundRequestJpaEntity request, RefundEventJpaEntity original,
                                                Integer adminId, String eventKey, String fingerprint,
                                                String reasonCode, Instant now) {
        return new ReviewEventConflict(request.getId(), request.getUserId(), adminId, original.getId(),
                original.getOperationType(), eventKey, original.getEventFingerprint(), fingerprint, reasonCode, now);
    }

    public static final class ReviewEventConflict extends RuntimeException {
        private final Long requestId;
        private final Integer ownerId;
        private final Integer adminId;
        private final Long originalEventId;
        private final String operation;
        private final String eventKey;
        private final String originalFingerprint;
        private final String conflictingFingerprint;
        private final String reasonCode;
        private final Instant observedAt;

        private ReviewEventConflict(Long requestId, Integer ownerId, Integer adminId, Long originalEventId,
                                    String operation, String eventKey, String originalFingerprint,
                                    String conflictingFingerprint, String reasonCode, Instant observedAt) {
            super("Admin review event identity has conflicting evidence");
            this.requestId = requestId; this.ownerId = ownerId; this.adminId = adminId;
            this.originalEventId = originalEventId; this.operation = operation; this.eventKey = eventKey;
            this.originalFingerprint = originalFingerprint; this.conflictingFingerprint = conflictingFingerprint;
            this.reasonCode = reasonCode; this.observedAt = observedAt;
        }
        public Long requestId() { return requestId; }
        public Integer ownerId() { return ownerId; }
        public Integer adminId() { return adminId; }
        public Long originalEventId() { return originalEventId; }
        public String operation() { return operation; }
        public String eventKey() { return eventKey; }
        public String originalFingerprint() { return originalFingerprint; }
        public String conflictingFingerprint() { return conflictingFingerprint; }
        public String reasonCode() { return reasonCode; }
        public Instant observedAt() { return observedAt; }
    }
}
