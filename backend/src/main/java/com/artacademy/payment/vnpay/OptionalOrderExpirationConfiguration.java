package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.repository.PaymentEventConflictRepository;
import com.drawingcourse.backend.repository.PaymentEventRepository;
import com.drawingcourse.backend.repository.PaymentSettlementRepository;
import com.drawingcourse.backend.service.expiration.CoursePurchaseReleasePort;
import com.drawingcourse.backend.service.expiration.InventoryReservationReleasePort;
import com.drawingcourse.backend.service.expiration.OrderExpirationCoordinator;
import com.drawingcourse.backend.service.expiration.VoucherExpirationReleasePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** Default-off Course-only expiration composition using C's real release participant. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.order.expiration", name = "enabled", havingValue = "true")
class OptionalOrderExpirationConfiguration {

    @Bean
    OrderExpirationCoordinator orderExpirationCoordinator(OrderRepository orders,
            PaymentAttemptRepository attempts, PaymentEventRepository events,
            PaymentEventConflictRepository conflicts, PaymentSettlementRepository settlements,
            VoucherExpirationReleasePort vouchers,
            ObjectProvider<InventoryReservationReleasePort> inventory,
            CoursePurchaseReleasePort courses, PlatformTransactionManager transactionManager) {
        return new OrderExpirationCoordinator(orders, attempts, events, conflicts, settlements, vouchers,
                inventory.getIfAvailable(), courses, Clock.systemUTC(), transactionManager);
    }
}
