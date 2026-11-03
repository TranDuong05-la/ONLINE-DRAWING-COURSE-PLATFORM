package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.service.settlement.SettlementCoordinator;
import com.drawingcourse.backend.service.settlement.SettlementWorkClaimService;
import com.drawingcourse.backend.service.settlement.SettlementWorkerOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit worker composition is separate from its scheduler activation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.payment.vnpay", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.payment.settlement", name = {"enabled", "worker.enabled"}, havingValue = "true")
class OptionalSettlementWorkerConfiguration {

    @Bean
    SettlementWorkerOrchestrator settlementWorkerOrchestrator(SettlementWorkClaimService claims,
            SettlementCoordinator coordinator) {
        return new SettlementWorkerOrchestrator(claims, coordinator);
    }
}
