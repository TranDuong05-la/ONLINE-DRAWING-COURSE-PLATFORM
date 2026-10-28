package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.entity.PaymentAttempt;
import com.drawingcourse.backend.entity.PaymentStatus;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.service.OrderPersistenceMapper;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates/reuses local VNPay attempt metadata only. The order row lock serializes creation
 * for an Order; all attempt writers must take that same lock. No remote gateway call occurs here.
 */
public class PaymentSessionApplicationService {
    private static final String PROVIDER = "vnpay";
    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final VnPayPaymentUrlBuilder urls;
    private final Clock clock;
    private final OrderPersistenceMapper orderMapper = new OrderPersistenceMapper();
    private final PaymentPersistenceMapper attemptMapper = new PaymentPersistenceMapper();

    public PaymentSessionApplicationService(OrderRepository orders, PaymentAttemptRepository attempts,
                                            VnPayPaymentUrlBuilder urls, Clock clock) {
        this.orders = Objects.requireNonNull(orders);
        this.attempts = Objects.requireNonNull(attempts);
        this.urls = Objects.requireNonNull(urls);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public PaymentSession createOrReuse(Integer authenticatedUserId, Integer orderId,
                                       Instant trustedRequestTime, String clientIp) {
        if (authenticatedUserId == null || authenticatedUserId <= 0 || orderId == null || orderId <= 0)
            throw new IllegalArgumentException("Authenticated user and Order IDs are required");
        Objects.requireNonNull(trustedRequestTime, "trustedRequestTime");
        var orderRow = orders.findForUpdate(orderId, authenticatedUserId).orElseThrow(PaymentSessionApplicationService::notFound);
        if (!orderId.equals(orderRow.getId()) || !authenticatedUserId.equals(orderRow.getUserId())) throw notFound();
        var order = orderMapper.toDomain(orderRow);
        Instant now = currentTime(trustedRequestTime);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT)
            throw new IllegalStateException("Order is not awaiting payment");
        if (!now.isBefore(order.getExpiresAt()))
            throw new IllegalStateException("Order payment deadline has expired");
        validateAmount(order.getTotalVnd());

        var existingRows = attempts.findAllForOrderUpdate(orderId, authenticatedUserId);
        var existing = existingRows.stream().map(attemptMapper::toDomain).toList();
        now = currentTime(trustedRequestTime);
        validateOrderDeadline(order, now);
        if (existing.stream().anyMatch(a -> a.status() == PaymentStatus.SUCCEEDED
                || a.status() == PaymentStatus.UNKNOWN || a.reviewReason() != PaymentAttempt.ReviewReason.NONE))
            throw new IllegalStateException("Existing payment attempt requires reconciliation");
        var active = existing.stream().filter(PaymentSessionApplicationService::active).toList();
        if (active.size() > 1) throw new IllegalStateException("Order has multiple active payment attempts; reconciliation required");
        if (!active.isEmpty()) return session(orderMapper.toStored(orderRow), active.getFirst(), clientIp,
                currentTime(trustedRequestTime), true);

        PaymentAttempt next;
        if (existing.isEmpty()) {
            next = PaymentAttempt.create(orderId, order, PROVIDER, merchantReference(), Clock.fixed(now, clock.getZone()));
        } else {
            var latest = existing.getLast();
            if (latest.status() != PaymentStatus.FAILED)
                throw new IllegalStateException("Existing payment attempt is terminal and cannot be retried safely");
            next = latest.retry(merchantReference(), order, Clock.fixed(now, clock.getZone()));
        }

        // Flush inside this REQUIRED transaction so URL/config failures roll the new row back.
        var row = attempts.saveAndFlush(attemptMapper.toNewAttempt(next));
        var persisted = attemptMapper.toDomain(row);
        return session(orderMapper.toStored(orderRow), persisted, clientIp, currentTime(trustedRequestTime), false);
    }

    private Instant currentTime(Instant trustedRequestTime) {
        Instant current = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (trustedRequestTime.isAfter(current)) throw new IllegalArgumentException("Request time cannot be in the future");
        return current;
    }

    private static void validateOrderDeadline(com.drawingcourse.backend.entity.Order order, Instant now) {
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT)
            throw new IllegalStateException("Order is not awaiting payment");
        if (!now.isBefore(order.getExpiresAt()))
            throw new IllegalStateException("Order payment deadline has expired");
    }

    private PaymentSession session(com.drawingcourse.backend.service.StoredOrder order, PaymentAttempt attempt,
                                   String clientIp, Instant now, boolean reused) {
        String url = urls.build(order, attempt, clientIp, now);
        return new PaymentSession(order.id(), attempt.id(), attempt.merchantReference(), url, attempt.expiresAt(), reused);
    }

    private static boolean active(PaymentAttempt attempt) {
        return (attempt.status() == PaymentStatus.CREATED || attempt.status() == PaymentStatus.PENDING)
                && attempt.reviewReason() == PaymentAttempt.ReviewReason.NONE;
    }

    private static void validateAmount(long amountVnd) {
        if (amountVnd <= 0) throw new IllegalStateException("Order amount is not payable");
        try {
            if (Math.multiplyExact(amountVnd, 100L) > 999_999_999_999L)
                throw new IllegalStateException("Order amount exceeds the VNPay limit");
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("Order amount exceeds the VNPay limit", ex);
        }
    }

    private static String merchantReference() { return "dca-" + UUID.randomUUID().toString().replace("-", ""); }
    private static java.util.NoSuchElementException notFound() {
        return new java.util.NoSuchElementException("Order not found");
    }
}
