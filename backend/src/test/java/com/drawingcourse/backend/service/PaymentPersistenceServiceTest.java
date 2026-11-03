package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentPersistenceServiceTest {
    private final PaymentAttemptRepository attempts=mock(PaymentAttemptRepository.class);
    private final PaymentEventRepository events=mock(PaymentEventRepository.class);
    private final OrderRepository orders=mock(OrderRepository.class);
    private final OrderCurrentUser identity=mock(OrderCurrentUser.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final Instant now=Instant.parse("2026-10-24T00:00:00Z");
    private final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    private final PaymentPersistenceService service=new PaymentPersistenceService(attempts,events,orders,identity,clock,manager);
    private Order order;
    @BeforeEach void setup() {
        when(identity.requireUserId()).thenReturn(10);
        when(manager.getTransaction(any())).thenAnswer(i->mock(TransactionStatus.class));
        order=Order.create(10,List.of(new OrderLineSnapshot(OrderItemType.COURSE,1,"Course",1,100,true)),0,Optional.empty(),clock);
        var row=OrderJpaEntity.fromSnapshot(order); ReflectionTestUtils.setField(row,"id",20);
        when(orders.findByIdAndUserId(20,10)).thenReturn(Optional.of(row));
    }
    private PaymentAttemptJpaEntity row() {
        var row=PaymentAttemptJpaEntity.newSnapshot(PaymentAttempt.create(20,order,"provider","merchant",clock));
        ReflectionTestUtils.setField(row,"id",1); ReflectionTestUtils.setField(row,"version",0L); return row;
    }
    @Test void createsExpectedAmountFromOwnerScopedOrderAndJoinsLocalTransaction() {
        when(attempts.saveAndFlush(any())).thenAnswer(i->{ PaymentAttemptJpaEntity row=i.getArgument(0); ReflectionTestUtils.setField(row,"id",1); ReflectionTestUtils.setField(row,"version",0L); return row; });
        var result=service.create(20,"provider","merchant");
        assertEquals(100,result.attempt().expectedAmountVnd()); assertEquals(now.plusSeconds(1800),result.attempt().expiresAt());
        verify(manager).getTransaction(argThat(t->t.getPropagationBehavior()==TransactionDefinition.PROPAGATION_REQUIRED));
        verify(manager).commit(any()); verifyNoInteractions(events);
    }
    @Test void sameMerchantReferenceReplaysMetadataWithoutNewInsertOrOrderRead() {
        when(attempts.findByProviderAndMerchantReferenceAndUserId("provider","merchant",10)).thenReturn(Optional.of(row()));
        var result=service.create(20,"provider","merchant"); assertEquals(1,result.attempt().id());
        verify(attempts,never()).saveAndFlush(any()); verifyNoInteractions(orders,events);
        assertThrows(IllegalStateException.class,()->service.create(21,"provider","merchant"));
    }
    @Test void readsAreOwnerScopedAndForeignRowsAreHidden() {
        when(attempts.findByIdAndUserId(1,10)).thenReturn(Optional.of(row()));
        assertEquals(1,service.load(1).attempt().id());
        var foreign=row(); ReflectionTestUtils.setField(foreign,"userId",11);
        when(attempts.findByIdAndUserId(1,10)).thenReturn(Optional.of(foreign));
        assertThrows(NoSuchElementException.class,()->service.load(1));
        assertThrows(NoSuchElementException.class,()->service.load(2));
    }
    @Test void absentAuthenticationStopsAllPersistence() {
        when(identity.requireUserId()).thenReturn(null);
        assertThrows(SecurityException.class,()->service.create(20,"provider","merchant"));
        assertThrows(SecurityException.class,()->service.load(1));
        assertThrows(SecurityException.class,()->service.loadEvent("provider","e1"));
        verifyNoInteractions(attempts,events,orders,manager);
    }
    @Test void failedInsertRollsBackAndDoesNotRetryOrCallGateway() {
        when(attempts.saveAndFlush(any())).thenThrow(new IllegalStateException("unique key or persistence failure"));
        assertThrows(IllegalStateException.class,()->service.create(20,"provider","merchant"));
        verify(attempts,times(1)).saveAndFlush(any()); verify(manager).rollback(any()); verifyNoInteractions(events);
    }

    @Test void rejectsOrderRowsWhoseOwnerDoesNotMatchAuthenticatedIdentity() {
        var foreignOrder = Order.create(11, List.of(new OrderLineSnapshot(OrderItemType.COURSE, 2, "Foreign", 1, 100, true)), 0, Optional.empty(), clock);
        var row = OrderJpaEntity.fromSnapshot(foreignOrder);
        ReflectionTestUtils.setField(row, "id", 21);
        when(orders.findByIdAndUserId(21, 10)).thenReturn(Optional.of(row));
        assertThrows(NoSuchElementException.class, () -> service.create(21, "provider", "merchant-foreign"));
        verify(attempts, never()).saveAndFlush(any());
    }
}
