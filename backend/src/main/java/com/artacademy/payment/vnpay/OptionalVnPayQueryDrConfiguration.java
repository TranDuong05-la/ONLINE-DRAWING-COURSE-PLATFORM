package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/** Server-side QueryDR exists only in explicitly selected local-demo or test profiles. */
@Configuration(proxyBeanMethods = false)
@Profile({"mysql-local", "test"})
@ConditionalOnProperty(prefix = "app.payment.vnpay", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.payment.vnpay.query-dr", name = "enabled", havingValue = "true")
class OptionalVnPayQueryDrConfiguration {
    @Bean
    VnPayQueryDrVerifier vnPayQueryDrVerifier(VnPayProperties properties) {
        return new VnPayQueryDrVerifier(properties);
    }

    @Bean
    VnPayQueryDrTransport vnPayQueryDrTransport(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new HttpVnPayQueryDrTransport(mapper);
    }

    @Bean
    VnPayQueryDrService vnPayQueryDrService(VnPayProperties properties, PaymentAttemptRepository attempts,
            VnPayQueryDrTransport transport, VnPayQueryDrVerifier verifier,
            VnPayIpnReconciliationService reconciliation, Clock paymentSessionClock) {
        return new VnPayQueryDrService(properties, attempts, transport, verifier, reconciliation, paymentSessionClock);
    }
}
