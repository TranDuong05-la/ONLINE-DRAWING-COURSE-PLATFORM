package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderPersistenceServiceTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository items = mock(OrderItemRepository.class);
    private final OrderCurrentUser current = mock(OrderCurrentUser.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final OrderPersistenceService service = new OrderPersistenceService(orders, items, current, transactions);
    private Order domain;
    private OrderJpaEntity row;

    @BeforeEach void setup() {
        when(current.requireUserId()).thenReturn(10);
        when(transactions.getTransaction(any())).thenAnswer(i -> mock(TransactionStatus.class));
        domain = Order.create(10, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "A", 3, 10, true)),
                5, Optional.of(new OrderVoucher("ONE", OrderVoucher.Kind.FIXED_VND, 1, 1)));
        row = new OrderPersistenceMapper().toNewEntity(domain);
        ReflectionTestUtils.setField(row, "id", 20);
        ReflectionTestUtils.setField(row, "version", 0L);
        ReflectionTestUtils.setField(row.getItems().getFirst(), "id", 30);
    }

    @Test void loadScopesOwnerAndReturnsIdentityWithExactAmounts() {
        when(orders.findByIdAndUserId(20, 10)).thenReturn(Optional.of(row));
        var result = service.load(20);
        assertEquals(20, result.id());
        assertEquals(30, result.itemIdentities().getFirst().id());
        assertEquals(29, result.snapshot().getItems().getFirst().netAmountVnd());
        assertEquals(34, result.snapshot().getTotalVnd());
        verify(transactions).getTransaction(argThat(d -> d.isReadOnly()
                && d.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(orders, never()).saveAndFlush(any());
        verify(transactions).commit(any());
    }

    @Test void itemReadIsScopedToBothOrderAndOwner() {
        when(items.findByIdAndOrder_IdAndOrder_UserId(30, 20, 10)).thenReturn(Optional.of(row.getItems().getFirst()));
        var result = service.loadItem(20, 30);
        assertEquals(30, result.id());
        assertEquals(20, result.orderId());
        assertEquals(10, result.snapshot().netAmountForUnits(0, 1));
        verifyNoInteractions(orders);
    }

    @Test void missingAndForeignOrdersUseSameNotFoundOutcome() {
        when(orders.findByIdAndUserId(20, 10)).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> service.load(20));
        ReflectionTestUtils.setField(row, "userId", 11);
        when(orders.findByIdAndUserId(20, 10)).thenReturn(Optional.of(row));
        assertThrows(NoSuchElementException.class, () -> service.load(20));
        verify(orders, never()).saveAndFlush(any());
    }

    @Test void foreignOrWrongOrderItemsAreRejectedEvenIfAdapterReturnsThem() {
        when(items.findByIdAndOrder_IdAndOrder_UserId(30, 21, 10)).thenReturn(Optional.of(row.getItems().getFirst()));
        assertThrows(NoSuchElementException.class, () -> service.loadItem(21, 30));
        ReflectionTestUtils.setField(row, "userId", 11);
        when(items.findByIdAndOrder_IdAndOrder_UserId(30, 20, 10)).thenReturn(Optional.of(row.getItems().getFirst()));
        assertThrows(NoSuchElementException.class, () -> service.loadItem(20, 30));
        assertThrows(NoSuchElementException.class, () -> service.loadItem(20, 31));
    }

    @Test void insertJoinsFutureCheckoutAndPreservesDomainDeadline() {
        var expiry = domain.getExpiresAt();
        when(orders.saveAndFlush(any())).thenAnswer(invocation -> {
            OrderJpaEntity saved = invocation.getArgument(0);
            assertNull(saved.getId());
            assertEquals(expiry, saved.getPaymentExpiresAt());
            ReflectionTestUtils.setField(saved, "id", 20);
            ReflectionTestUtils.setField(saved, "version", 0L);
            ReflectionTestUtils.setField(saved.getItems().getFirst(), "id", 30);
            return saved;
        });
        var stored = service.insertNew(domain);
        assertEquals(20, stored.id());
        assertEquals(30, stored.itemIdentities().getFirst().id());
        assertEquals(expiry, stored.paymentExpiresAt());
        assertEquals(domain.getItems(), stored.snapshot().getItems());
        verify(transactions).getTransaction(argThat(d -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED));
        verify(transactions).commit(any());
    }

    @Test void fixedExpirationIsApplied() {
        when(orders.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        assertEquals(domain.getCreatedAt().plusSeconds(1800), service.insertNew(domain).paymentExpiresAt());
    }

    @Test void legacyMissingOrDifferentDeadlineCannotBeInsertedAsNewOrder() {
        ReflectionTestUtils.setField(row, "paymentExpiresAt", null);
        var mapper = new OrderPersistenceMapper();
        var missing = mapper.toDomain(row);
        assertThrows(IllegalArgumentException.class, () -> service.insertNew(missing));
        ReflectionTestUtils.setField(row, "paymentExpiresAt", domain.getCreatedAt().plusSeconds(900));
        var legacy = mapper.toDomain(row);
        assertThrows(IllegalArgumentException.class, () -> service.insertNew(legacy));
        verifyNoInteractions(orders, items, transactions);
    }

    @Test void ownerMismatchAndHistoricalTerminalInsertFailBeforePersistence() {
        when(current.requireUserId()).thenReturn(11);
        assertThrows(SecurityException.class, () -> service.insertNew(domain));
        when(current.requireUserId()).thenReturn(10);
        ReflectionTestUtils.setField(row, "status", OrderStatus.COMPLETED);
        var historical = new OrderPersistenceMapper().toDomain(row);
        assertThrows(IllegalArgumentException.class, () -> service.insertNew(historical));
        verifyNoInteractions(orders, items, transactions);
    }

    @Test void missingAuthenticationBlocksAllOperationsBeforeDatabaseAccess() {
        when(current.requireUserId()).thenReturn(null);
        assertThrows(SecurityException.class, () -> service.load(20));
        assertThrows(SecurityException.class, () -> service.loadItem(20, 30));
        assertThrows(SecurityException.class, () -> service.insertNew(domain));
        verifyNoInteractions(orders, items, transactions);
    }

    @Test void persistenceConflictsPropagateAndRollbackWithoutBlindRetries() {
        when(orders.saveAndFlush(any())).thenThrow(new OptimisticLockingFailureException("conflict"));
        assertThrows(OptimisticLockingFailureException.class, () -> service.insertNew(domain));
        verify(orders, times(1)).saveAndFlush(any());
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }
}
