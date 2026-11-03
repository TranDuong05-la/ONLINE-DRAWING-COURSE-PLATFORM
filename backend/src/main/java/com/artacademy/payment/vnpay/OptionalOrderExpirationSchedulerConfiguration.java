package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.service.expiration.OrderExpirationCoordinator;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.entity.OrderStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/** Expiration timer has an independent default-off gate from the expiration coordinator itself. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.order.expiration", name = {"enabled", "scheduler.enabled"}, havingValue = "true")
class OptionalOrderExpirationSchedulerConfiguration {

    @Bean
    OrderExpirationScheduler orderExpirationScheduler(OrderExpirationCoordinator expiration,
            OrderRepository orders, Environment environment) {
        return new OrderExpirationScheduler(expiration,
                orders, environment.getProperty("app.order.expiration.scheduler.batch-size", Integer.class, 100));
    }

    static final class OrderExpirationScheduler {
        private final OrderExpirationCoordinator expiration;
        private final OrderRepository orders;
        private final int batchSize;

        OrderExpirationScheduler(OrderExpirationCoordinator expiration, OrderRepository orders, int batchSize) {
            this.expiration = expiration;
            this.orders = orders;
            this.batchSize = batchSize;
        }

        @Scheduled(fixedDelayString = "${app.order.expiration.scheduler.fixed-delay-ms:60000}",
                initialDelayString = "${app.order.expiration.scheduler.initial-delay-ms:15000}")
        public void expireDueOrders() {
            var due = orders.findDueOrderIds(OrderStatus.PENDING_PAYMENT,
                    Clock.systemUTC().instant().truncatedTo(ChronoUnit.MICROS),
                    org.springframework.data.domain.PageRequest.of(0, batchSize));
            for (Integer orderId : due) expiration.expire(orderId);
        }
    }
}
