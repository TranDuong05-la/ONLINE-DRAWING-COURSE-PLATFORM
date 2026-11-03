package com.drawingcourse.backend.service.refund;

import com.drawingcourse.backend.entity.RefundReviewCaseJpaEntity;
import com.drawingcourse.backend.repository.RefundEventRepository;
import com.drawingcourse.backend.repository.RefundRequestRepository;
import com.drawingcourse.backend.repository.RefundReviewCaseRepository;
import com.drawingcourse.backend.repository.RefundUnitAllocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Commits conflict evidence independently after the rejected business transaction has ended. */
@Service
public class RefundConflictReviewService {
    private final RefundRequestRepository requests;
    private final RefundEventRepository events;
    private final RefundReviewCaseRepository reviews;
    private final RefundUnitAllocationRepository units;

    public RefundConflictReviewService(RefundRequestRepository requests, RefundEventRepository events,
                                       RefundReviewCaseRepository reviews, RefundUnitAllocationRepository units) {
        this.requests = requests; this.events = events; this.reviews = reviews; this.units = units;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundReviewCaseJpaEntity recordEventConflict(RefundEventStoreService.EventIdentityConflict conflict) {
        var command = conflict.conflicting();
        requests.findForUpdate(command.requestId(), command.userId()).orElseThrow();
        var original = events.findByRefundRequestIdAndOperationTypeAndEventKey(
                command.requestId(), command.operationType(), command.eventKey()).orElseThrow();
        if (original.getEventFingerprint().equals(command.eventFingerprint()))
            throw new IllegalStateException("Event is now an idempotent replay, not a conflict");
        String key = sha("event|" + command.requestId() + "|" + command.operationType() + "|"
                + command.eventKey() + "|" + command.eventFingerprint());
        return reviews.findByRefundRequestIdAndCaseKey(command.requestId(), key).orElseGet(() ->
                reviews.saveAndFlush(RefundReviewCaseJpaEntity.eventConflict(command.requestId(),
                        command.providerAttemptId(), original.getId(), key, original.getEventFingerprint(),
                        command.eventFingerprint(), command.evidence() == null ? "local-conflict" : command.evidence().reference(),
                        command.evidence() == null ? command.eventFingerprint() : command.evidence().fingerprint(),
                        "Rejected changed fingerprint for " + command.operationType() + "/" + command.eventKey(),
                        command.recordedAt())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundReviewCaseJpaEntity recordRequestConflict(Integer userId,
                                                            RefundPersistenceService.RequestIdempotencyConflict conflict) {
        var request = requests.findForUpdate(conflict.requestId(), userId).orElseThrow();
        if (!request.getRequestFingerprint().equals(conflict.originalFingerprint()))
            throw new IllegalStateException("Original request fingerprint changed unexpectedly");
        String key = sha("request|" + conflict.requestId() + "|" + conflict.conflictingFingerprint());
        return reviews.findByRefundRequestIdAndCaseKey(conflict.requestId(), key).orElseGet(() ->
                reviews.saveAndFlush(RefundReviewCaseJpaEntity.requestConflict(conflict.requestId(), key,
                        conflict.originalFingerprint(), conflict.conflictingFingerprint(),
                        "Rejected changed fingerprint for owner idempotency key", conflict.observedAt())));
    }

    /** Resolves only a winner committed by a concurrent owner-key insert; unrelated constraint failures stay errors. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RequestRaceResolution resolveRequestInsertRace(Integer userId, String idempotencyKey,
                                                          String candidateFingerprint, Instant observedAt) {
        var candidate = requests.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElse(null);
        if (candidate == null) return null;
        // Serialize conflict-case lookup/creation with the other request-level audit paths.
        var winner = requests.findForUpdate(candidate.getId(), userId).orElse(null);
        if (winner == null) return null;
        if (winner.getRequestFingerprint().equals(candidateFingerprint))
            return new RequestRaceResolution(new RefundPersistenceService.CreationResult(winner,
                    units.findAllByRefundRequestIdOrderByUnitPosition(winner.getId()), true), null);
        String key = sha("request|" + winner.getId() + "|" + candidateFingerprint);
        var review = reviews.findByRefundRequestIdAndCaseKey(winner.getId(), key).orElseGet(() ->
                reviews.saveAndFlush(RefundReviewCaseJpaEntity.requestConflict(winner.getId(), key,
                        winner.getRequestFingerprint(), candidateFingerprint,
                        "Rejected concurrent changed fingerprint for owner idempotency key", observedAt)));
        return new RequestRaceResolution(null, review);
    }

    /** Resolves only the authoritative event-identity winner after a concurrent unique insert. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventRaceResolution resolveEventInsertRace(RefundEventStoreService.EventCommand command) {
        requests.findForUpdate(command.requestId(), command.userId()).orElseThrow();
        var original = events.findByRefundRequestIdAndOperationTypeAndEventKey(
                command.requestId(), command.operationType(), command.eventKey()).orElse(null);
        if (original == null) return null;
        if (original.getEventFingerprint().equals(command.eventFingerprint()))
            return new EventRaceResolution(original, null);
        String key = sha("event|" + command.requestId() + "|" + command.operationType() + "|"
                + command.eventKey() + "|" + command.eventFingerprint());
        var review = reviews.findByRefundRequestIdAndCaseKey(command.requestId(), key).orElseGet(() ->
                reviews.saveAndFlush(RefundReviewCaseJpaEntity.eventConflict(command.requestId(),
                        command.providerAttemptId(), original.getId(), key, original.getEventFingerprint(),
                        command.eventFingerprint(), command.evidence() == null ? "local-conflict" : command.evidence().reference(),
                        command.evidence() == null ? command.eventFingerprint() : command.evidence().fingerprint(),
                        "Rejected concurrent changed fingerprint for " + command.operationType() + "/" + command.eventKey(),
                        command.recordedAt())));
        return new EventRaceResolution(null, review);
    }

    /** Commits a changed Admin decision replay only after the decision transaction has rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundReviewCaseJpaEntity recordAdminDecisionConflict(Long requestId, Integer ownerId, Integer adminId,
                                                                 Long originalEventId, String operation, String eventKey,
                                                                 String originalFingerprint, String conflictingFingerprint,
                                                                 String reasonCode, Instant observedAt) {
        requests.findForUpdate(requestId, ownerId).orElseThrow();
        var original = events.findById(originalEventId).orElseThrow();
        if (!original.getRefundRequestId().equals(requestId)
                || !original.getOperationType().equals(operation)
                || !original.getEventFingerprint().equals(originalFingerprint))
            throw new IllegalStateException("Original Admin decision event changed before conflict audit");
        String key = sha("admin-review|" + requestId + "|" + operation + "|" + eventKey + "|"
                + conflictingFingerprint);
        return reviews.findByRefundRequestIdAndCaseKey(requestId, key).orElseGet(() ->
                reviews.saveAndFlush(RefundReviewCaseJpaEntity.eventConflict(requestId, null, originalEventId,
                        key, originalFingerprint, conflictingFingerprint, "admin:" + adminId,
                        conflictingFingerprint, "Changed Admin decision replay; operation=" + operation
                                + "; eventKey=" + eventKey + "; reason=" + reasonCode,
                        observedAt)));
    }

    public record RequestRaceResolution(RefundPersistenceService.CreationResult replay,
                                        RefundReviewCaseJpaEntity conflictReview) { }
    public record EventRaceResolution(com.drawingcourse.backend.entity.RefundEventJpaEntity replay,
                                      RefundReviewCaseJpaEntity conflictReview) { }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
