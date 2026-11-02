package com.artacademy.bootstrap;

import com.drawingcourse.backend.service.settlement.SettlementTimeoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SettlementTimeoutConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsAreBoundAndDisabled() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            SettlementTimeoutProperties properties = context.getBean(SettlementTimeoutProperties.class);
            assertFalse(properties.isEnabled());
            assertEquals(Duration.ofSeconds(120), properties.getLeaseDuration());
            assertEquals(Duration.ofSeconds(60), properties.getTransactionTimeout());
            assertEquals(Duration.ofSeconds(5), properties.getClaimTransactionTimeout());
            assertEquals(Duration.ofSeconds(2), properties.getConnectionAcquisitionTimeout());
            assertEquals(Duration.ofSeconds(3), properties.getConnectionEstablishmentTimeout());
            assertEquals(Duration.ofSeconds(3), properties.getMysqlLockWaitTimeout());
            assertEquals(Duration.ofSeconds(5), properties.getStatementTimeout());
            assertEquals(Duration.ofSeconds(15), properties.getSocketTimeout());
        });
    }

    @Test
    void candidateDurationsCanBeOverriddenWithoutEnablingSettlement() {
        contextRunner.withPropertyValues(
                "app.payment.settlement.lease-duration=180s",
                "app.payment.settlement.transaction-timeout=75s",
                "app.payment.settlement.statement-timeout=4s")
            .run(context -> {
                assertNull(context.getStartupFailure());
                SettlementTimeoutProperties properties = context.getBean(SettlementTimeoutProperties.class);
                assertEquals(Duration.ofSeconds(180), properties.getLeaseDuration());
                assertEquals(Duration.ofSeconds(75), properties.getTransactionTimeout());
                assertEquals(Duration.ofSeconds(4), properties.getStatementTimeout());
                assertFalse(properties.isEnabled());
            });
    }

    @Test
    void nonPositiveOrUnsafeTimingRelationshipsFailBinding() {
        contextRunner.withPropertyValues("app.payment.settlement.lease-duration=30s")
                .run(context -> assertNotNull(context.getStartupFailure()));
        contextRunner.withPropertyValues("app.payment.settlement.transaction-timeout=0s")
                .run(context -> assertNotNull(context.getStartupFailure()));
        contextRunner.withPropertyValues("app.payment.settlement.statement-timeout=500ms")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SettlementTimeoutProperties.class)
    static class PropertiesConfiguration { }
}
