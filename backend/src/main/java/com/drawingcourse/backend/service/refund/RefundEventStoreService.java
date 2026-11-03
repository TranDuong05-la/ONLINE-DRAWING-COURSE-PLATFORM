package com.drawingcourse.backend.service.refund;

import com.drawingcourse.backend.entity.RefundEventJpaEntity;
import com.drawingcourse.backend.repository.RefundEventRepository;
import com.drawingcourse.backend.repository.RefundRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/** One transactional authoritative-event write. Conflict auditing is deliberately a separate transaction. */
@Service
public class RefundEventStoreService {
    private final RefundRequestRepository requests;
    private final RefundEventRepository events;

    public RefundEventStoreService(RefundRequestRepository requests, RefundEventRepository events) {
        this.requests = requests; this.events = events;
    }

    @Transactional
    public EventResult record(EventCommand command) {
        Objects.requireNonNull(command, "command");
        requests.findForUpdate(command.requestId, command.userId).orElseThrow();
        var existing = events.findByRefundRequestIdAndOperationTypeAndEventKey(
                command.requestId, command.operationType, command.eventKey);
        if (existing.isPresent()) {
            if (existing.get().getEventFingerprint().equals(command.eventFingerprint))
                return new EventResult(existing.get(), true);
            throw new EventIdentityConflict(existing.get(), command);
        }
        var row = RefundEventJpaEntity.applied(command.requestId, command.providerAttemptId,
                command.operationType, command.eventKey, command.eventFingerprint,
                command.stateBeforeFingerprint, command.stateAfterFingerprint, command.evidence,
                command.occurredAt, command.recordedAt);
        return new EventResult(events.saveAndFlush(row), false);
    }

    public record EventCommand(Long requestId, Integer userId, Long providerAttemptId,
                               String operationType, String eventKey, String eventFingerprint,
                               String stateBeforeFingerprint, String stateAfterFingerprint,
                               RefundEventJpaEntity.EvidenceSnapshot evidence,
                               Instant occurredAt, Instant recordedAt) { }
    public record EventResult(RefundEventJpaEntity event, boolean idempotentReplay) { }
    static final class EventIdentityConflict extends RuntimeException {
        private final RefundEventJpaEntity original; private final EventCommand conflicting;
        EventIdentityConflict(RefundEventJpaEntity original, EventCommand conflicting) {
            super("Refund event identity has a different fingerprint"); this.original=original; this.conflicting=conflicting;
        }
        RefundEventJpaEntity original() { return original; }
        EventCommand conflicting() { return conflicting; }
    }
}
