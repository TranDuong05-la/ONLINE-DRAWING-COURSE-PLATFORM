package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.service.settlement.SettlementWorkerOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Timer activation is an independent opt-in on top of worker composition. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.payment.vnpay", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.payment.settlement", name = {"enabled", "worker.enabled", "worker.scheduler.enabled"}, havingValue = "true")
class OptionalSettlementSchedulerConfiguration {

    @Bean
    SettlementWorkerScheduler settlementWorkerScheduler(SettlementWorkerOrchestrator worker,
            org.springframework.core.env.Environment environment) {
        int batchSize = environment.getProperty("app.payment.settlement.worker.batch-size", Integer.class, 16);
        String workerId = environment.getProperty("app.payment.settlement.worker.worker-id", "course-settlement-worker");
        return new SettlementWorkerScheduler(worker, workerId, batchSize);
    }

    static final class SettlementWorkerScheduler {
        private final SettlementWorkerOrchestrator worker;
        private final String workerId;
        private final int batchSize;

        SettlementWorkerScheduler(SettlementWorkerOrchestrator worker, String workerId, int batchSize) {
            this.worker = worker;
            this.workerId = workerId;
            this.batchSize = batchSize;
        }

        @Scheduled(fixedDelayString = "${app.payment.settlement.worker.scheduler.fixed-delay-ms:10000}",
                initialDelayString = "${app.payment.settlement.worker.scheduler.initial-delay-ms:10000}")
        public void pollOnce() {
            worker.runBatch(workerId, batchSize);
        }
    }
}
