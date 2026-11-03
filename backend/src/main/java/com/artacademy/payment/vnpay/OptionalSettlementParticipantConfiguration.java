package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentSettlementRepository;
import com.drawingcourse.backend.repository.VoucherClaimRepository;
import com.drawingcourse.backend.repository.VoucherRepository;
import com.drawingcourse.backend.repository.VoucherUserUsageRepository;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import com.drawingcourse.backend.service.settlement.SettlementLedgerPort;
import com.drawingcourse.backend.service.settlement.VoucherConsumptionPort;
import com.drawingcourse.backend.service.settlement.JpaSettlementLedgerAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Registers real local participants only when the explicitly gated settlement or expiration path is enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${app.payment.settlement.enabled:false} or ${app.order.expiration.enabled:false}")
class OptionalSettlementParticipantConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.payment.settlement", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(SettlementLedgerPort.class)
    SettlementLedgerPort settlementLedgerParticipant(PaymentSettlementRepository settlements) {
        return new JpaSettlementLedgerAdapter(settlements);
    }

    @Bean
    @ConditionalOnMissingBean(VoucherConsumptionPort.class)
    JpaVoucherReservationService settlementVoucherParticipant(VoucherRepository vouchers,
            VoucherUserUsageRepository userUsage, VoucherClaimRepository claims, OrderRepository orders,
            PlatformTransactionManager transactionManager) {
        return new JpaVoucherReservationService(vouchers, userUsage, claims, orders, transactionManager);
    }
}
