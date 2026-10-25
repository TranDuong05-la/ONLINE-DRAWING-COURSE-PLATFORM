package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;

import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;

/** Unwired local metadata persistence. Never initiates a charge or applies provider events. */
public final class PaymentPersistenceService {
    public record AttemptState(Long version,PaymentAttempt attempt) { }
    private final PaymentAttemptRepository attempts;
    private final PaymentEventRepository events;
    private final OrderRepository orders;
    private final OrderCurrentUser identity;
    private final Clock clock;
    private final PaymentPersistenceMapper mapper=new PaymentPersistenceMapper();
    private final TransactionTemplate reads,writes;
    public PaymentPersistenceService(PaymentAttemptRepository attempts,PaymentEventRepository events,OrderRepository orders,
            OrderCurrentUser identity,Clock clock,PlatformTransactionManager manager) {
        this.attempts=Objects.requireNonNull(attempts); this.events=Objects.requireNonNull(events); this.orders=Objects.requireNonNull(orders);
        this.identity=Objects.requireNonNull(identity); this.clock=Objects.requireNonNull(clock);
        reads=new TransactionTemplate(Objects.requireNonNull(manager)); reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes=new TransactionTemplate(manager); writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }
    public AttemptState load(Integer id) {
        Integer user=user(); Objects.requireNonNull(id);
        return reads.execute(status -> {
            var row=attempts.findByIdAndUserId(id,user).orElseThrow(PaymentPersistenceService::notFound);
            if(!id.equals(row.getId()) || !user.equals(row.getUserId())) throw notFound();
            return new AttemptState(row.getVersion(),mapper.toDomain(row));
        });
    }
    public PaymentEventAudit loadEvent(String provider,String eventReference) {
        Integer user=user();
        return reads.execute(status -> {
            var row=events.findByProviderAndEventReferenceAndUserId(provider,eventReference,user).orElseThrow(PaymentPersistenceService::notFound);
            if(!user.equals(row.getUserId()) || !Objects.equals(provider,row.getProvider()) || !Objects.equals(eventReference,row.getEventReference())) throw notFound();
            return mapper.toDomain(row);
        });
    }
    /** One merchant key replays metadata only. Concurrent creation still relies on the DB unique key. */
    public AttemptState create(Integer orderId,String provider,String merchantReference) {
        Integer user=user(); Objects.requireNonNull(orderId); Objects.requireNonNull(provider); Objects.requireNonNull(merchantReference);
        return writes.execute(status -> {
            var previous=attempts.findByProviderAndMerchantReferenceAndUserId(provider,merchantReference,user);
            if(previous.isPresent()) {
                var row=previous.get();
                if(!user.equals(row.getUserId()) || !provider.equals(row.getProvider()) || !merchantReference.equals(row.getMerchantReference())
                        || !orderId.equals(row.getOrderId())) throw new IllegalStateException("Merchant reference conflicts with existing attempt");
                return new AttemptState(row.getVersion(),mapper.toDomain(row));
            }
            var row=orders.findByIdAndUserId(orderId,user).orElseThrow(PaymentPersistenceService::notFound);
            if(!orderId.equals(row.getId()) || !user.equals(row.getUserId())) throw notFound();
            var domain=new OrderPersistenceMapper().toDomain(row);
            var saved=attempts.saveAndFlush(mapper.toNewAttempt(PaymentAttempt.create(orderId,domain,provider,merchantReference,clock)));
            return new AttemptState(saved.getVersion(),mapper.toDomain(saved));
        });
    }
    private Integer user() { Integer user=identity.requireUserId(); if(user==null) throw new SecurityException("Authentication required"); return user; }
    private static NoSuchElementException notFound() { return new NoSuchElementException("Payment attempt or event not found"); }
}
