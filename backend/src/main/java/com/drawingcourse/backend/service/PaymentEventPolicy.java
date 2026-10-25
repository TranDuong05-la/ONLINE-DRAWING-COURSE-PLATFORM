package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import java.time.Instant;
import java.util.*;
import static com.drawingcourse.backend.entity.PaymentAttempt.ReviewReason.*;

/** Pure evaluation of trusted events. No callback, database handler or Order mutation. */
public final class PaymentEventPolicy {
    public record Result(PaymentAttempt attempt, PaymentEventAudit audit, boolean duplicate) { }
    public Result evaluate(PaymentAttempt attempt, PaymentEvent event, OrderStatus currentOrderStatus,
                           Optional<PaymentEventAudit> previous) {
        Objects.requireNonNull(attempt.id(),"Persist an attempt before auditing events");
        Objects.requireNonNull(currentOrderStatus); Objects.requireNonNull(previous);
        if (previous.isPresent()) {
            var old = previous.get();
            if (!Objects.equals(old.attemptId(),attempt.id()) || !old.event().provider().equals(event.provider())
                    || !old.event().eventReference().equals(event.eventReference()) || !old.fingerprint().equals(event.fingerprint()))
                throw new IllegalStateException("Payment event identity reused with different facts/attempt; reconcile");
            return new Result(attempt,old,true);
        }
        var reason = NONE;
        if (!event.provider().equals(attempt.provider()) || !event.merchantReference().equals(attempt.merchantReference())) reason=ROUTING_MISMATCH;
        else if (!event.currency().equals(attempt.currency())) reason=CURRENCY_MISMATCH;
        else if (event.amountVnd()!=attempt.expectedAmountVnd()) reason=AMOUNT_MISMATCH;
        else if (event.occurredAt().isBefore(attempt.createdAt()) || event.receivedAt().isBefore(attempt.updatedAt())
                || event.occurredAt().isAfter(event.receivedAt())) reason=INVALID_EVENT_TIME;
        var status=attempt.status(); var transaction=attempt.transactionReference(); var succeeded=attempt.succeededAt();
        if (reason==NONE && event.outcome()==PaymentEvent.Outcome.SUCCEEDED) {
            if (status==PaymentStatus.SUCCEEDED) {
                if (!Objects.equals(transaction,event.transactionReference()) || !Objects.equals(succeeded,event.occurredAt())) reason=CONFLICTING_SUCCESS;
            } else {
                status=PaymentStatus.SUCCEEDED; transaction=event.transactionReference(); succeeded=event.occurredAt();
                if (!event.receivedAt().isBefore(attempt.expiresAt()) || !event.occurredAt().isBefore(attempt.expiresAt())) reason=LATE_SUCCESS;
                else if (currentOrderStatus!=OrderStatus.PENDING_PAYMENT) reason=ORDER_NOT_PAYABLE;
            }
        } else if (reason==NONE && status!=PaymentStatus.SUCCEEDED) {
            if (event.outcome()==PaymentEvent.Outcome.FAILED && status!=PaymentStatus.CANCELLED && status!=PaymentStatus.EXPIRED) status=PaymentStatus.FAILED;
            else if (event.outcome()==PaymentEvent.Outcome.CANCELLED && (status==PaymentStatus.CREATED || status==PaymentStatus.PENDING)) status=PaymentStatus.CANCELLED;
            else if (event.outcome()==PaymentEvent.Outcome.PENDING && status==PaymentStatus.CREATED) status=PaymentStatus.PENDING;
            else if (event.outcome()==PaymentEvent.Outcome.UNKNOWN && (status==PaymentStatus.CREATED || status==PaymentStatus.PENDING)) status=PaymentStatus.UNKNOWN;
        }
        // Review is sticky; no later event silently clears a reconciliation requirement.
        var retainedReview=attempt.requiresReconciliation()?attempt.reviewReason():reason;
        Instant updated=event.receivedAt().isBefore(attempt.updatedAt())?attempt.updatedAt():event.receivedAt();
        var result=new PaymentAttempt(attempt.id(),attempt.orderId(),attempt.userId(),attempt.provider(),attempt.merchantReference(),
                attempt.expectedAmountVnd(),attempt.currency(),attempt.orderCreatedAt(),attempt.expiresAt(),attempt.createdAt(),
                updated,status,transaction,succeeded,retainedReview);
        var disposition=reason!=NONE?PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED:
                status!=attempt.status()?PaymentEventAudit.Disposition.APPLIED:PaymentEventAudit.Disposition.IGNORED;
        var audit=new PaymentEventAudit(null,attempt.id(),attempt.orderId(),attempt.userId(),event,event.fingerprint(),
                attempt.status(),status,disposition,reason,currentOrderStatus);
        return new Result(result,audit,false);
    }
}
