package com.drawingcourse.backend.service.refund;

import com.drawingcourse.backend.entity.RefundReviewCaseJpaEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Non-transactional orchestrator: rejected operation rolls back before conflict evidence commits. */
@Service
public class RefundAuditedEventService {
    private final RefundEventStoreService eventStore;
    private final RefundConflictReviewService conflictReviews;

    public RefundAuditedEventService(RefundEventStoreService eventStore, RefundConflictReviewService conflictReviews) {
        this.eventStore = eventStore; this.conflictReviews = conflictReviews;
    }

    public RefundEventStoreService.EventResult record(RefundEventStoreService.EventCommand command) {
        try {
            return eventStore.record(command);
        } catch (RefundEventStoreService.EventIdentityConflict conflict) {
            RefundReviewCaseJpaEntity review = conflictReviews.recordEventConflict(conflict);
            throw new DurableEventConflict(review.getId());
        } catch (DataIntegrityViolationException race) {
            var resolution = conflictReviews.resolveEventInsertRace(command);
            if (resolution == null) throw race;
            if (resolution.replay() != null)
                return new RefundEventStoreService.EventResult(resolution.replay(), true);
            throw new DurableEventConflict(resolution.conflictReview().getId());
        }
    }

    public static final class DurableEventConflict extends RuntimeException {
        private final Long reviewCaseId;
        DurableEventConflict(Long reviewCaseId) {
            super("Refund event conflict was durably recorded"); this.reviewCaseId = reviewCaseId;
        }
        public Long reviewCaseId() { return reviewCaseId; }
    }
}
