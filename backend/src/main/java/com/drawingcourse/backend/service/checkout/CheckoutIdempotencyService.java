package com.drawingcourse.backend.service.checkout;

import com.artacademy.entity.User;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.entity.CheckoutRequestJpaEntity;
import com.drawingcourse.backend.entity.CheckoutRequestState;
import com.drawingcourse.backend.repository.CheckoutRequestRepository;
import com.drawingcourse.backend.repository.OrderRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable Checkout request coordination only. Call from the Checkout transaction that also writes its Order.
 * This class is intentionally not a Spring component and has no Cart, catalog, payment, or reservation dependency.
 */
public final class CheckoutIdempotencyService {
    private static final Pattern FINGERPRINT = Pattern.compile("v1:[0-9a-f]{64}");
    private static final String CLAIM_CONSTRAINT = "uk_checkout_requests_user_key";

    private final CheckoutRequestRepository requests;
    private final UserRepository users;
    private final OrderRepository orders;
    private final Clock clock;
    private final TransactionTemplate required;

    public CheckoutIdempotencyService(CheckoutRequestRepository requests, UserRepository users,
                                      OrderRepository orders, Clock clock,
                                      PlatformTransactionManager transactionManager) {
        this.requests = Objects.requireNonNull(requests);
        this.users = Objects.requireNonNull(users);
        this.orders = Objects.requireNonNull(orders);
        this.clock = Objects.requireNonNull(clock);
        this.required = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** The returned new claim is not durable until the caller commits its enclosing Checkout transaction. */
    public ClaimResult claim(Integer userId, String idempotencyKey, String fingerprint) {
        validate(userId, idempotencyKey, fingerprint);
        requireCallerTransaction();
        return required.execute(status -> {
            var existing = requests.findByUser_IdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) return existingResult(existing.orElseThrow(), userId, fingerprint);

            User user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("Unknown Checkout user"));
            var claim = new CheckoutRequestJpaEntity(user, idempotencyKey, fingerprint,
                    clock.instant().truncatedTo(ChronoUnit.MICROS));
            try {
                var stored = requests.saveAndFlush(claim);
                return new Claimed(stored.getId());
            } catch (DataIntegrityViolationException failure) {
                if (violatesNamedConstraint(failure, CLAIM_CONSTRAINT)) {
                    // The enclosing transaction must roll back. A retry then observes the winning row.
                    throw new ConcurrentClaimException();
                }
                throw failure;
            }
        });
    }

    /** Completes a claim in the same transaction as the new Order insert. */
    public void complete(Integer userId, Integer requestId, Integer orderId) {
        if (userId == null || userId <= 0 || requestId == null || requestId <= 0 || orderId == null || orderId <= 0)
            throw new IllegalArgumentException("User, Checkout request and Order IDs must be positive");
        requireCallerTransaction();
        required.executeWithoutResult(status -> {
            var claim = requests.findByIdAndUserIdForUpdate(requestId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("Checkout request not found"));
            var order = orders.findByIdAndUserId(orderId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found for Checkout owner"));
            claim.complete(order, clock.instant().truncatedTo(ChronoUnit.MICROS));
            requests.saveAndFlush(claim);
        });
    }

    private ClaimResult existingResult(CheckoutRequestJpaEntity row, Integer userId, String fingerprint) {
        if (!row.getRequestFingerprint().equals(fingerprint)) return new FingerprintConflict();
        if (row.getState() == CheckoutRequestState.PROCESSING) return new InProgress(row.getId());
        Integer orderId = row.getOrderId();
        if (orderId == null || orders.findByIdAndUserId(orderId, userId).isEmpty())
            throw new IllegalStateException("Completed Checkout request has no owner-scoped Order");
        return new Replayed(orderId);
    }

    private static void validate(Integer userId, String key, String fingerprint) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("User ID must be positive");
        if (key == null || key.isBlank() || key.length() > 64)
            throw new IllegalArgumentException("Idempotency key must contain 1 to 64 characters");
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches())
            throw new IllegalArgumentException("Unsupported Checkout fingerprint format");
    }

    private static void requireCallerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Checkout idempotency must join the enclosing Checkout transaction");
    }

    private static boolean violatesNamedConstraint(Throwable failure, String name) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && name.equalsIgnoreCase(violation.getConstraintName()))
                return true;
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains(name.toLowerCase(java.util.Locale.ROOT)))
                return true;
        }
        return false;
    }

    public sealed interface ClaimResult permits Claimed, Replayed, FingerprintConflict, InProgress {}
    public record Claimed(Integer requestId) implements ClaimResult {}
    public record Replayed(Integer orderId) implements ClaimResult {}
    public record FingerprintConflict() implements ClaimResult {}
    public record InProgress(Integer requestId) implements ClaimResult {}

    /** Retryable conflict raised after a concurrent unique-key winner; caller transaction is rollback-only. */
    public static final class ConcurrentClaimException extends RuntimeException {
        public ConcurrentClaimException() { super("Checkout idempotency key is being claimed concurrently; retry the request"); }
    }
}
