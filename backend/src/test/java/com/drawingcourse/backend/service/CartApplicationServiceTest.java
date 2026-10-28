package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.repository.*;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import java.sql.SQLException;
import java.util.NoSuchElementException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CartApplicationServiceTest {
    private final CartRepository carts = mock(CartRepository.class);
    private final CartItemRepository items = mock(CartItemRepository.class);
    private final CartCurrentUser current = mock(CartCurrentUser.class);
    @SuppressWarnings("unchecked")
    private final CartCatalog<Integer, Integer> catalog = mock(CartCatalog.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final CartApplicationService service = new CartApplicationService(carts, items, current, catalog, transactions);
    private CartJpaEntity record;

    @BeforeEach void setup() {
        when(current.requireUserId()).thenReturn(10);
        when(transactions.getTransaction(any())).thenAnswer(i -> mock(TransactionStatus.class));
        record = new CartJpaEntity(10);
        ReflectionTestUtils.setField(record, "id", 1);
        when(carts.findByUserId(10)).thenReturn(Optional.of(record));
        when(carts.findByUserIdForUpdate(10)).thenReturn(Optional.of(record));
        when(carts.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test void missingReadsAndClearDoNotCreateRows() {
        when(carts.findByUserId(10)).thenReturn(Optional.empty());
        when(carts.findByUserIdForUpdate(10)).thenReturn(Optional.empty());
        var missing = service.load();
        assertNull(missing.cartId());
        assertEquals(0L, missing.revision());
        var cleared = service.clear();
        assertTrue(cleared.items().isEmpty());
        assertEquals(0L, cleared.revision());
        assertThrows(NoSuchElementException.class, () -> service.update(99, 1));
        assertThrows(NoSuchElementException.class, () -> service.remove(99));
        verify(carts, never()).saveAndFlush(any());
        verifyNoInteractions(items, catalog);
        verify(transactions, times(2)).getTransaction(argThat(d -> d.isReadOnly()
                && d.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
    }

    @Test void accumulatesProductsAndValidatesFinalQuantity() {
        record.addItem(CartItemType.PRODUCT, 20, 2);
        var row = record.getItems().getFirst();
        ReflectionTestUtils.setField(row, "id", 99);
        var result = service.add(CartItemType.PRODUCT, 20, 3);
        assertEquals(5, result.items().getFirst().quantity());
        assertEquals(99, result.items().getFirst().cartItemId());
        assertEquals(1, result.items().size());
        assertEquals(1L, result.revision());
        verify(catalog).requirePurchasable(eq(10), argThat(i -> i.getQuantity() == 3));
        verify(transactions).getTransaction(argThat(d -> !d.isReadOnly()
                && d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactions).commit(any());
    }

    @Test void coursesAreIdempotentAndRejectQuantityTwo() {
        var first = service.add(CartItemType.COURSE, 20, 1);
        assertEquals(1L, first.revision());
        clearInvocations(catalog);
        var duplicate = service.add(CartItemType.COURSE, 20, 1);
        assertEquals(1L, duplicate.revision());
        assertEquals(1, record.getItems().size());
        assertEquals(1, record.getItems().getFirst().getQuantity());
        verifyNoInteractions(catalog);
        ReflectionTestUtils.setField(record.getItems().getFirst(), "id", 99);
        assertThrows(IllegalArgumentException.class, () -> service.add(CartItemType.COURSE, 20, 2));
        var row = record.getItems().getFirst();
        when(items.findByIdAndCart_IdAndCart_UserId(99, 1, 10)).thenReturn(Optional.of(row));
        assertThrows(IllegalArgumentException.class, () -> service.update(99, 2));
    }

    @Test void successfulContentMutationsIncrementOnceAndNoopsDoNotIncrement() {
        record.addItem(CartItemType.PRODUCT, 20, 2);
        var row = record.getItems().getFirst();
        ReflectionTestUtils.setField(row, "id", 99);
        assertEquals(0L, service.load().revision());
        when(items.findByIdAndCart_IdAndCart_UserId(99, 1, 10)).thenReturn(Optional.of(row));
        assertEquals(1L, service.update(99, 4).revision());
        assertEquals(1L, service.update(99, 4).revision());
        assertEquals(2L, service.remove(99).revision());
        assertEquals(2L, service.clear().revision());
    }

    @Test void failedMutationDoesNotAdvanceRevision() {
        record.addItem(CartItemType.COURSE, 20, 1);
        var row = record.getItems().getFirst();
        ReflectionTestUtils.setField(row, "id", 99);
        when(items.findByIdAndCart_IdAndCart_UserId(99, 1, 10)).thenReturn(Optional.of(row));
        assertThrows(IllegalArgumentException.class, () -> service.update(99, 2));
        assertEquals(0L, service.load().revision());
        assertEquals(1, record.getItems().size());
    }

    @Test void genuinelyNewCourseIsPreflightedBeforeWriteAndRemovedDuplicateIsRevalidated() {
        record.addItem(CartItemType.COURSE, 20, 1);
        var existing = record.getItems().getFirst();
        ReflectionTestUtils.setField(existing, "id", 99);
        when(carts.findByUserIdForUpdate(10)).thenAnswer(invocation -> {
            record.removeItem(existing);
            return Optional.of(record);
        });
        service.add(CartItemType.COURSE, 20, 1);
        verify(catalog).requirePurchasable(eq(10), argThat(item -> item.getItemType() == CartItemType.COURSE));
    }

    @Test void itemAccessIsScopedAndForeignItemsAreHidden() {
        when(items.findByIdAndCart_IdAndCart_UserId(99, 1, 10)).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> service.update(99, 1));
        assertThrows(NoSuchElementException.class, () -> service.remove(99));
        verify(carts, never()).saveAndFlush(any());
        verify(items).findByIdAndCart_IdAndCart_UserId(99, 1, 10);
        verifyNoInteractions(catalog);
    }

    @Test void updateAndRemoveKeepIdentityAndDoNotValidateCatalogOnDeletion() {
        record.addItem(CartItemType.PRODUCT, 20, 2);
        var row = record.getItems().getFirst();
        ReflectionTestUtils.setField(row, "id", 99);
        when(items.findByIdAndCart_IdAndCart_UserId(99, 1, 10)).thenReturn(Optional.of(row));
        assertEquals(99, service.update(99, 4).items().getFirst().cartItemId());
        assertEquals(4, row.getQuantity());
        clearInvocations(catalog);
        assertTrue(service.remove(99).items().isEmpty());
        verifyNoInteractions(catalog);
    }

    @Test void catalogFailureHappensBeforeStartingCartWriteTransaction() {
        doThrow(new IllegalArgumentException("Unavailable")).when(catalog).requirePurchasable(any(), any());
        assertThrows(IllegalArgumentException.class, () -> service.add(CartItemType.PRODUCT, 20, 1));
        assertTrue(record.getItems().isEmpty());
        verifyNoInteractions(transactions);
        verify(carts, never()).saveAndFlush(any());
    }

    @Test void catalogPreflightCompletesBeforeCartLockAndDeletionNeedsNoCatalog() {
        var order = inOrder(catalog, transactions, carts);
        service.add(CartItemType.PRODUCT, 20, 1);
        order.verify(catalog).requirePurchasable(eq(10), any());
        order.verify(transactions).getTransaction(any());
        order.verify(carts).findByUserIdForUpdate(10);
        record.addItem(CartItemType.PRODUCT, 30, 1);
        var row = record.getItems().getLast();
        ReflectionTestUtils.setField(row, "id", 88);
        when(items.findByIdAndCart_IdAndCart_UserId(88, 1, 10)).thenReturn(Optional.of(row));
        clearInvocations(catalog);
        service.remove(88);
        service.clear();
        verifyNoInteractions(catalog);
    }

    @Test void firstCartUniqueRaceRetriesInFreshTransaction() {
        when(carts.findByUserIdForUpdate(10)).thenReturn(Optional.empty(), Optional.of(record));
        var unique = new ConstraintViolationException("duplicate", new SQLException("duplicate", "23000", 1062),
                "uk_carts_user");
        when(carts.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", unique))
                .thenAnswer(i -> i.getArgument(0));
        assertEquals(1, service.add(CartItemType.PRODUCT, 20, 1).items().size());
        verify(transactions, times(2)).getTransaction(argThat(d ->
                d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactions).rollback(any());
        verify(transactions).commit(any());
        var order = inOrder(transactions, carts);
        order.verify(transactions).getTransaction(any());
        order.verify(carts).findByUserIdForUpdate(10);
        order.verify(carts).saveAndFlush(any());
        order.verify(transactions).rollback(any());
        order.verify(transactions).getTransaction(any());
    }

    @Test void unrelatedIntegrityFailureIsNotRetried() {
        when(carts.findByUserIdForUpdate(10)).thenReturn(Optional.empty());
        when(carts.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Other constraint"));
        assertThrows(DataIntegrityViolationException.class, () -> service.add(CartItemType.PRODUCT, 20, 1));
        verify(transactions, times(1)).getTransaction(any());
        verify(catalog).requirePurchasable(eq(10), any());
    }

    @Test void lockRetriesAreBounded() {
        when(carts.findByUserIdForUpdate(10)).thenThrow(new PessimisticLockingFailureException("deadlock"));
        assertThrows(PessimisticLockingFailureException.class, () -> service.add(CartItemType.PRODUCT, 20, 1));
        verify(transactions, times(3)).getTransaction(any());
        verify(transactions, times(3)).rollback(any());
        verify(catalog).requirePurchasable(eq(10), any());
    }

    @Test void createsFirstCartAndFlushesBeforeReturningSnapshot() {
        when(carts.findByUserIdForUpdate(10)).thenReturn(Optional.empty());
        when(carts.saveAndFlush(any())).thenAnswer(i -> {
            CartJpaEntity saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 3);
            saved.getItems().forEach(row -> ReflectionTestUtils.setField(row, "id", 99));
            return saved;
        });
        var result = service.add(CartItemType.PRODUCT, 20, 1);
        assertEquals(3, result.cartId());
        assertEquals(99, result.items().getFirst().cartItemId());
        verify(carts, times(2)).saveAndFlush(any());
    }

    @Test void missingAuthenticationFailsBeforeDatabaseAccess() {
        when(current.requireUserId()).thenReturn(null);
        assertThrows(SecurityException.class, service::load);
        assertThrows(SecurityException.class, () -> service.add(CartItemType.PRODUCT, 20, 1));
        verifyNoInteractions(carts, items, transactions, catalog);
    }

    @Test void repeatedFirstCartConflictsStopAfterThreeRolledBackTransactions() {
        when(carts.findByUserIdForUpdate(10)).thenReturn(Optional.empty());
        var unique = new ConstraintViolationException("duplicate", new SQLException("duplicate", "23000", 1062),
                "carts.uk_carts_user");
        when(carts.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", unique));
        assertThrows(RuntimeException.class, () -> service.add(CartItemType.PRODUCT, 20, 1));
        verify(carts, times(3)).saveAndFlush(any());
        verify(transactions, times(3)).getTransaction(any());
        verify(transactions, times(3)).rollback(any());
        verify(transactions, never()).commit(any());
        verify(catalog).requirePurchasable(eq(10), any());
    }

    @Test void rejectsForeignRecordEvenIfRepositoryViolatesScopedContract() {
        var foreignCart = new CartJpaEntity(11);
        ReflectionTestUtils.setField(foreignCart, "id", 2);
        foreignCart.addItem(CartItemType.PRODUCT, 20, 1);
        when(items.findByIdAndCart_IdAndCart_UserId(99, 1, 10))
                .thenReturn(Optional.of(foreignCart.getItems().getFirst()));
        assertThrows(NoSuchElementException.class, () -> service.update(99, 2));
        assertEquals(1, foreignCart.getItems().getFirst().getQuantity());
        verify(carts, never()).saveAndFlush(any());
        verifyNoInteractions(catalog);
    }
}
