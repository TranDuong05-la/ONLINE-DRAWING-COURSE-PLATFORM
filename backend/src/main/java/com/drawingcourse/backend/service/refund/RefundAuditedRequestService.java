package com.drawingcourse.backend.service.refund;

import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

/** Non-transactional request boundary that never reports a conflict before audit persistence commits. */
@Service
public class RefundAuditedRequestService {
    private final RefundPersistenceService persistence;
    private final RefundConflictReviewService conflicts;

    public RefundAuditedRequestService(RefundPersistenceService persistence, RefundConflictReviewService conflicts) {
        this.persistence = persistence; this.conflicts = conflicts;
    }

    public RefundPersistenceService.CreationResult create(RefundPersistenceService.CreateCommand command) {
        try {
            return persistence.create(command);
        } catch (RefundPersistenceService.RequestIdempotencyConflict conflict) {
            var review = conflicts.recordRequestConflict(command.userId(), conflict);
            throw new DurableRequestConflict(review.getId());
        } catch (DataIntegrityViolationException race) {
            var resolution = conflicts.resolveRequestInsertRace(command.userId(), command.idempotencyKey(),
                    command.requestFingerprint(), command.requestedAt());
            if (resolution == null) throw race;
            if (resolution.replay() != null) return resolution.replay();
            throw new DurableRequestConflict(resolution.conflictReview().getId());
        }
    }

    public static final class DurableRequestConflict extends RuntimeException {
        private final Long reviewCaseId;
        DurableRequestConflict(Long reviewCaseId) {
            super("Refund request conflict was durably recorded"); this.reviewCaseId = reviewCaseId;
        }
        public Long reviewCaseId() { return reviewCaseId; }
    }
}
