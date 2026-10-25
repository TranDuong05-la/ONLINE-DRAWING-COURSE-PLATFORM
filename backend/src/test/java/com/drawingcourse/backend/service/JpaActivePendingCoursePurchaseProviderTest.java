package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderStatus;
import com.drawingcourse.backend.repository.OrderRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaActivePendingCoursePurchaseProviderTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final JpaActivePendingCoursePurchaseProvider provider =
            new JpaActivePendingCoursePurchaseProvider(orders);

    @Test void databaseFailuresPropagateInsteadOfBeingReportedAsNoPendingPurchase() {
        Instant observedAt = Instant.parse("2026-10-25T12:00:00Z");
        when(orders.existsActivePendingCoursePurchase(17, 42, OrderItemType.COURSE,
                OrderStatus.PENDING_PAYMENT, observedAt))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> provider.hasActivePendingPurchase(17, 42, observedAt));
    }
}
