package com.drawingcourse.backend.service.refund;

import com.artacademy.security.AuthenticatedUserIdResolver;
import com.drawingcourse.backend.entity.RefundEventJpaEntity;
import com.drawingcourse.backend.entity.RefundRequestJpaEntity;
import com.drawingcourse.backend.entity.RefundStateMachine;
import com.drawingcourse.backend.repository.RefundEventRepository;
import com.drawingcourse.backend.repository.RefundRequestRepository;
import com.drawingcourse.backend.repository.RefundUnitAllocationRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

import static com.drawingcourse.backend.entity.RefundPersistenceTypes.*;

/**
 * Application boundary for the review transition currently safe without Member C/E adapters.
 * Course request rejection is supported; approval and Product review remain fail-closed.
 */
@Service
public class RefundAdminReviewService {
    private static final String REJECTION = RefundStateMachine.OperationType.ADMIN_REJECTION.name();

    private final AuthenticatedUserIdResolver users;
    private final RefundAdminReviewTransaction reviewTransaction;
    private final RefundConflictReviewService conflicts;

    public RefundAdminReviewService(AuthenticatedUserIdResolver users, RefundAdminReviewTransaction reviewTransaction,
                                    RefundConflictReviewService conflicts) {
        this.users = Objects.requireNonNull(users);
        this.reviewTransaction = Objects.requireNonNull(reviewTransaction);
        this.conflicts = Objects.requireNonNull(conflicts);
    }

    /** Actor identity and timestamp are server-derived; no actor ID or decision time comes from a command body. */
    public ReviewResult reject(Long requestId, String eventKey, String reasonCode) {
        Integer adminId = requireAdminId();
        String key = bounded(eventKey, 128, "eventKey");
        String reason = bounded(reasonCode, 64, "reasonCode");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String fingerprint = sha("refund-admin-rejection-v1|" + requestId + "|" + key + "|" + reason + "|" + adminId);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Admin review must own its transaction boundary");
        try {
            return reviewTransaction.reject(positiveId(requestId, "requestId"), adminId, key, reason, fingerprint, now);
        } catch (RefundAdminReviewTransaction.ReviewEventConflict conflict) {
            conflicts.recordAdminDecisionConflict(conflict.requestId(), conflict.ownerId(), conflict.adminId(),
                    conflict.originalEventId(), conflict.operation(), conflict.eventKey(),
                    conflict.originalFingerprint(), conflict.conflictingFingerprint(), conflict.reasonCode(),
                    conflict.observedAt());
            throw new DurableReviewConflictException(conflict.requestId(), conflict.eventKey());
        }
    }

    private Integer requireAdminId() {
        Integer id = users.requireUserId();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())))
            throw new AccessDeniedException("Admin role is required for Refund review");
        return id;
    }

    static String stateFingerprint(DecisionStatus decision, FinancialStatus financial,
                                   AmountReservationStatus amount, UnitAllocationStatus units) {
        return sha(decision + "|" + financial + "|" + amount + "|" + units);
    }
    private static Long positiveId(Long value, String name) {
        if (value == null || value <= 0) throw new IllegalArgumentException(name);
        return value;
    }
    private static String bounded(String value, int max, String name) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty() || result.length() > max) throw new IllegalArgumentException(name);
        return result;
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record ReviewResult(RefundRequestJpaEntity request, RefundEventJpaEntity event, boolean idempotentReplay) { }
    public static final class DurableReviewConflictException extends RuntimeException {
        public DurableReviewConflictException(Long requestId, String eventKey) {
            super("Conflicting Admin review was durably recorded for request " + requestId + " and event " + eventKey);
        }
    }
    public static final class RefundNotFoundException extends RuntimeException {
        public RefundNotFoundException(Long id) { super("Refund request not found: " + id); }
    }
    public static final class RefundDecisionConflictException extends RuntimeException {
        public RefundDecisionConflictException(String message) { super(message); }
    }
    public static final class ReviewUnavailableException extends RuntimeException {
        public ReviewUnavailableException(String message) { super(message); }
    }
}
