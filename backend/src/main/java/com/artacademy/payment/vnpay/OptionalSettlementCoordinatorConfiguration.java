package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.repository.PaymentEventRepository;
import com.drawingcourse.backend.service.settlement.CourseOwnershipConfirmationPort;
import com.drawingcourse.backend.service.settlement.InventoryReservationConfirmationPort;
import com.drawingcourse.backend.service.settlement.SettlementCoordinator;
import com.drawingcourse.backend.service.settlement.SettlementLedgerPort;
import com.drawingcourse.backend.service.settlement.VoucherConsumptionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Registers settlement only when explicitly enabled and every transactional business adapter is present. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.payment.settlement", name = "enabled", havingValue = "true")
class OptionalSettlementCoordinatorConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.payment.vnpay", name = "enabled", havingValue = "true")
    @ConditionalOnBean({OrderRepository.class, PaymentAttemptRepository.class, PaymentEventRepository.class,
            SettlementLedgerPort.class, CourseOwnershipConfirmationPort.class,
            InventoryReservationConfirmationPort.class, VoucherConsumptionPort.class})
    SettlementCoordinator settlementCoordinator(OrderRepository orders, PaymentAttemptRepository attempts,
            PaymentEventRepository events, SettlementLedgerPort ledger,
            CourseOwnershipConfirmationPort courses, InventoryReservationConfirmationPort inventory,
            VoucherConsumptionPort vouchers, Clock paymentSessionClock,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new SettlementCoordinator(orders, attempts, events, ledger, courses, inventory,
                vouchers, paymentSessionClock, transactionManager);
    }
}
