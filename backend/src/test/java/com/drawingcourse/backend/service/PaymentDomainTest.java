package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentDomainTest {
    private final Instant now=Instant.parse("2026-10-24T00:00:00.123456Z");
    private final Clock clock=Clock.fixed(now,ZoneId.of("Asia/Ho_Chi_Minh"));
    private final PaymentEventPolicy policy=new PaymentEventPolicy();
    private Order order() { return Order.create(10,List.of(new OrderLineSnapshot(OrderItemType.PRODUCT,1,"Brush",3,10,true)),5,
            Optional.of(new OrderVoucher("ONE",OrderVoucher.Kind.FIXED_VND,1,1)),clock); }
    private PaymentAttempt attempt() { return PaymentAttempt.create(20,order(),"provider","merchant-1",clock).withIdentity(1); }
    private PaymentEvent event(String id,PaymentEvent.Outcome outcome,long amount,String currency,int occurred,int received) {
        return new PaymentEvent("provider",id,"merchant-1",outcome==PaymentEvent.Outcome.SUCCEEDED?"txn-1":null,
                amount,currency,outcome,now.plusSeconds(occurred),now.plusSeconds(received),"a".repeat(64),"verification-1");
    }
    private PaymentEventPolicy.Result apply(PaymentAttempt attempt,PaymentEvent event) {
        return policy.evaluate(attempt,event,OrderStatus.PENDING_PAYMENT,Optional.empty());
    }

    @Test void attemptCopiesExactOrderNetShippingAndDeadline() {
        var order=order(); var attempt=attempt();
        assertEquals(34,attempt.expectedAmountVnd()); assertEquals("VND",attempt.currency());
        assertEquals(order.getExpiresAt(),attempt.expiresAt()); assertEquals(now.plusSeconds(1800),attempt.expiresAt());
        assertEquals(20,attempt.orderId()); assertEquals(1,attempt.id());
        assertEquals(29,order.getItems().getFirst().netAmountVnd());
    }
    @Test void verifiedOnTimeSuccessRecordsAttemptOnly() {
        var order=order(); var result=apply(attempt(),event("e1",PaymentEvent.Outcome.SUCCEEDED,34,"VND",60,61));
        assertEquals(PaymentStatus.SUCCEEDED,result.attempt().status());
        assertEquals("txn-1",result.attempt().transactionReference()); assertFalse(result.attempt().requiresReconciliation());
        assertEquals(PaymentEventAudit.Disposition.APPLIED,result.audit().disposition());
        assertEquals(OrderStatus.PENDING_PAYMENT,order.getStatus()); assertEquals(PaymentStatus.CREATED,order.getPaymentStatus());
    }
    @Test void failedAttemptAllowsDistinctRetryWithoutExtendingDeadline() {
        var pending=apply(attempt(),event("p",PaymentEvent.Outcome.PENDING,34,"VND",1,2)).attempt();
        assertEquals(PaymentStatus.PENDING,pending.status());
        var failed=apply(pending,event("f",PaymentEvent.Outcome.FAILED,34,"VND",3,4)).attempt();
        assertEquals(PaymentStatus.FAILED,failed.status());
        var retry=failed.retry("merchant-2",order(),Clock.offset(clock,Duration.ofSeconds(120)));
        assertNull(retry.id()); assertEquals(PaymentStatus.CREATED,retry.status());
        assertEquals(failed.expiresAt(),retry.expiresAt()); assertEquals(now.plusSeconds(120),retry.createdAt());
        assertThrows(IllegalStateException.class,()->failed.retry("merchant-1",order(),clock));
        assertThrows(RuntimeException.class,()->failed.retry("merchant-2",order(),Clock.offset(clock,Duration.ofMinutes(30))));
        assertEquals(OrderStatus.PENDING_PAYMENT,order().getStatus());
    }
    @Test void duplicateEventIgnoresReceiptTimeAndDoesNotApplyTwice() {
        var first=apply(attempt(),event("e1",PaymentEvent.Outcome.SUCCEEDED,34,"VND",1,2));
        var replay=policy.evaluate(first.attempt(),event("e1",PaymentEvent.Outcome.SUCCEEDED,34,"VND",1,1900),
                OrderStatus.COMPLETED,Optional.of(first.audit()));
        assertTrue(replay.duplicate()); assertSame(first.attempt(),replay.attempt()); assertSame(first.audit(),replay.audit());
    }
    @Test void sameEventIdentityWithChangedFactsOrDifferentAttemptConflicts() {
        var first=apply(attempt(),event("e1",PaymentEvent.Outcome.SUCCEEDED,34,"VND",1,2));
        assertThrows(IllegalStateException.class,()->policy.evaluate(first.attempt(),event("e1",PaymentEvent.Outcome.SUCCEEDED,35,"VND",1,2),OrderStatus.PENDING_PAYMENT,Optional.of(first.audit())));
        var changedBody=new PaymentEvent("provider","e1","merchant-1","txn-1",34,"VND",PaymentEvent.Outcome.SUCCEEDED,
                now.plusSeconds(1),now.plusSeconds(2),"b".repeat(64),"verification-1");
        assertThrows(IllegalStateException.class,()->policy.evaluate(first.attempt(),changedBody,OrderStatus.PENDING_PAYMENT,Optional.of(first.audit())));
        var other=PaymentAttempt.create(20,order(),"provider","merchant-1",clock).withIdentity(2);
        assertThrows(IllegalStateException.class,()->policy.evaluate(other,first.audit().event(),OrderStatus.PENDING_PAYMENT,Optional.of(first.audit())));
    }
    @Test void amountAndCurrencyMismatchesRetainEvidenceWithoutAcceptingSettlement() {
        var amount=apply(attempt(),event("e1",PaymentEvent.Outcome.SUCCEEDED,Long.MAX_VALUE,"VND",1,2));
        assertEquals(PaymentAttempt.ReviewReason.AMOUNT_MISMATCH,amount.attempt().reviewReason());
        assertEquals(PaymentStatus.CREATED,amount.attempt().status()); assertEquals(Long.MAX_VALUE,amount.audit().event().amountVnd());
        var currency=apply(attempt(),event("e2",PaymentEvent.Outcome.SUCCEEDED,34,"USD",1,2));
        assertEquals(PaymentAttempt.ReviewReason.CURRENCY_MISMATCH,currency.attempt().reviewReason());
        assertEquals("USD",currency.audit().event().currency());
    }
    @Test void routingMismatchIsAuditedAndReviewIsNeverSilentlyCleared() {
        var event=new PaymentEvent("other","e1","wrong","txn-1",34,"VND",PaymentEvent.Outcome.SUCCEEDED,
                now.plusSeconds(1),now.plusSeconds(2),"a".repeat(64),"verified");
        var rejected=apply(attempt(),event);
        assertEquals(PaymentAttempt.ReviewReason.ROUTING_MISMATCH,rejected.attempt().reviewReason());
        var later=apply(rejected.attempt(),event("e2",PaymentEvent.Outcome.SUCCEEDED,34,"VND",3,4));
        assertTrue(later.attempt().requiresReconciliation());
    }
    @Test void lateSuccessRecordsFundsForReconciliationWithoutReactivation() {
        for (int received:List.of(1800,1801)) {
            var late=policy.evaluate(attempt(),event("e"+received,PaymentEvent.Outcome.SUCCEEDED,34,"VND",1700,received),OrderStatus.EXPIRED,Optional.empty());
            assertEquals(PaymentStatus.SUCCEEDED,late.attempt().status());
            assertEquals(PaymentAttempt.ReviewReason.LATE_SUCCESS,late.attempt().reviewReason());
            assertEquals(OrderStatus.EXPIRED,late.audit().observedOrderStatus());
            assertEquals(PaymentEventAudit.Disposition.RECONCILIATION_REQUIRED,late.audit().disposition());
            assertEquals(now.plusSeconds(1800),late.attempt().expiresAt());
        }
    }
    @Test void successOnCancelledOrAlreadyFulfilledOrderNeedsReview() {
        for (var status:List.of(OrderStatus.CANCELLED,OrderStatus.CONFIRMED,OrderStatus.COMPLETED)) {
            var result=policy.evaluate(attempt(),event("e1",PaymentEvent.Outcome.SUCCEEDED,34,"VND",1,2),status,Optional.empty());
            assertEquals(PaymentAttempt.ReviewReason.ORDER_NOT_PAYABLE,result.attempt().reviewReason());
        }
    }
    @Test void failureDoesNotDowngradeSuccessAndConflictingSuccessDoesNotReplaceOriginalReference() {
        var paid=apply(attempt(),event("e1",PaymentEvent.Outcome.SUCCEEDED,34,"VND",1,2)).attempt();
        var failure=apply(paid,event("f",PaymentEvent.Outcome.FAILED,34,"VND",3,4));
        assertEquals(PaymentStatus.SUCCEEDED,failure.attempt().status()); assertEquals(PaymentEventAudit.Disposition.IGNORED,failure.audit().disposition());
        var other=new PaymentEvent("provider","e2","merchant-1","txn-2",34,"VND",PaymentEvent.Outcome.SUCCEEDED,
                now.plusSeconds(3),now.plusSeconds(4),"a".repeat(64),"verified");
        var conflict=apply(paid,other);
        assertEquals(PaymentAttempt.ReviewReason.CONFLICTING_SUCCESS,conflict.attempt().reviewReason());
        assertEquals("txn-1",conflict.attempt().transactionReference());
    }
    @Test void unknownOutcomeAndInvalidEventTimeFailClosed() {
        var unknown=apply(attempt(),event("u",PaymentEvent.Outcome.UNKNOWN,34,"VND",1,2)).attempt();
        assertEquals(PaymentStatus.UNKNOWN,unknown.status());
        assertThrows(IllegalStateException.class,()->unknown.retry("merchant-2",order(),clock));
        assertEquals(PaymentAttempt.ReviewReason.INVALID_EVENT_TIME,apply(attempt(),event("future",PaymentEvent.Outcome.SUCCEEDED,34,"VND",4,3)).attempt().reviewReason());
    }
    @Test void persistenceRoundTripsAttemptAndAuditWithoutRecalculatingOrder() {
        var mapper=new PaymentPersistenceMapper();
        var fresh=PaymentAttempt.create(20,order(),"provider","merchant-1",clock);
        var row=mapper.toNewAttempt(fresh); ReflectionTestUtils.setField(row,"id",1); ReflectionTestUtils.setField(row,"version",2L);
        assertEquals(fresh.withIdentity(1),mapper.toDomain(row));
        var result=apply(mapper.toDomain(row),event("e1",PaymentEvent.Outcome.SUCCEEDED,34,"VND",1801,1802));
        var auditRow=mapper.toNewEvent(result.audit()); ReflectionTestUtils.setField(auditRow,"id",5);
        var restored=mapper.toDomain(auditRow);
        assertEquals(5,restored.id()); assertEquals(result.audit().event(),restored.event());
        assertEquals(result.audit().fingerprint(),restored.fingerprint()); assertEquals(PaymentAttempt.ReviewReason.LATE_SUCCESS,restored.reason());
        assertThrows(IllegalArgumentException.class,()->mapper.toNewAttempt(mapper.toDomain(row)));
        assertThrows(IllegalArgumentException.class,()->mapper.toNewEvent(restored));
    }
    @Test void rejectsInvalidEventsAttemptsAndPreservesUtcPrecision() {
        assertThrows(IllegalArgumentException.class,()->event("e1",PaymentEvent.Outcome.SUCCEEDED,-1,"VND",1,2));
        assertThrows(IllegalArgumentException.class,()->new PaymentEvent("provider","e1","merchant-1",null,34,"VND",PaymentEvent.Outcome.SUCCEEDED,now,now,"a".repeat(64),"verified"));
        assertThrows(IllegalArgumentException.class,()->attempt().withIdentity(2));
        var converter=new PaymentUtcInstantConverter();
        assertEquals(now,converter.convertToEntityAttribute(converter.convertToDatabaseColumn(now)));
        assertEquals(LocalDateTime.ofInstant(now,ZoneOffset.UTC),converter.convertToDatabaseColumn(now));
        assertThrows(IllegalArgumentException.class,()->converter.convertToDatabaseColumn(now.plusNanos(1)));
    }
}
