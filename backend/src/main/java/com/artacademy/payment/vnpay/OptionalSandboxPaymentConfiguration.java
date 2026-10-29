package com.artacademy.payment.vnpay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.repository.PaymentEventRepository;
import com.drawingcourse.backend.repository.PaymentEventConflictRepository;
import com.drawingcourse.backend.repository.PaymentReconciliationCaseRepository;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;
import java.time.Clock;

/** Binds optional sandbox settings only when explicitly enabled; registers no payment processor. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.payment.vnpay", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(VnPayProperties.class)
class OptionalSandboxPaymentConfiguration {
    @Bean @ConditionalOnMissingBean
    Clock paymentSessionClock() { return Clock.systemUTC(); }

    @Bean
    VnPayPaymentUrlBuilder vnPayPaymentUrlBuilder(VnPayProperties properties) {
        return new VnPayPaymentUrlBuilder(properties);
    }

    @Bean
    VnPayCallbackVerifier vnPayCallbackVerifier(VnPayProperties properties) {
        return new VnPayCallbackVerifier(properties);
    }

    @Bean
    PaymentSessionApplicationService paymentSessionApplicationService(OrderRepository orders,
            PaymentAttemptRepository attempts, VnPayPaymentUrlBuilder urlBuilder, Clock paymentSessionClock) {
        return new PaymentSessionApplicationService(orders, attempts, urlBuilder, paymentSessionClock);
    }

    @Bean
    VnPayIpnReconciliationService vnPayIpnReconciliationService(VnPayProperties properties,
            PaymentAttemptRepository attempts, PaymentEventRepository events,
            PaymentEventConflictRepository conflicts, PaymentReconciliationCaseRepository cases,
            OrderRepository orders, VnPayCallbackVerifier verifier, Clock paymentSessionClock,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new VnPayIpnReconciliationService(properties, attempts, events, conflicts, cases, orders,
                verifier, paymentSessionClock, transactionManager);
    }
}
