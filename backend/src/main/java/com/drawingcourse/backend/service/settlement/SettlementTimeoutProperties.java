package com.drawingcourse.backend.service.settlement;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/** Settlement-only candidate budgets. Pool, driver, and MySQL fields are descriptive until explicitly wired. */
@Validated
@ConfigurationProperties(prefix = "app.payment.settlement")
public class SettlementTimeoutProperties {
    private boolean enabled;

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration leaseDuration = Duration.ofSeconds(120);
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration transactionTimeout = Duration.ofSeconds(60);
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration claimTransactionTimeout = Duration.ofSeconds(5);

    // Deployment candidates only: these cannot be scoped to the settlement transaction safely with the shared pool.
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration connectionAcquisitionTimeout = Duration.ofSeconds(2);
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration connectionEstablishmentTimeout = Duration.ofSeconds(3);
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration mysqlLockWaitTimeout = Duration.ofSeconds(3);
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration statementTimeout = Duration.ofSeconds(5);
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration socketTimeout = Duration.ofSeconds(15);

    @AssertTrue(message = "settlement timeout candidates must be positive, statement timeout must be integral seconds, and transaction timeout must be shorter than lease duration")
    public boolean isTimeoutConfigurationValid() {
        return positive(leaseDuration) && positive(transactionTimeout) && positive(claimTransactionTimeout)
                && positive(connectionAcquisitionTimeout) && positive(connectionEstablishmentTimeout)
                && positive(mysqlLockWaitTimeout) && positive(statementTimeout) && positive(socketTimeout)
                && transactionTimeout.compareTo(leaseDuration) < 0
                && statementTimeout.compareTo(transactionTimeout) <= 0
                && wholeSeconds(transactionTimeout) && wholeSeconds(claimTransactionTimeout)
                && statementTimeout.getNano() == 0
                && statementTimeout.getSeconds() <= Integer.MAX_VALUE;
    }

    private static boolean positive(Duration value) { return value != null && !value.isNegative() && !value.isZero(); }
    private static boolean wholeSeconds(Duration value) {
        return positive(value) && value.getNano() == 0 && value.getSeconds() <= Integer.MAX_VALUE;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getTransactionTimeout() { return transactionTimeout; }
    public void setTransactionTimeout(Duration transactionTimeout) { this.transactionTimeout = transactionTimeout; }
    public Duration getClaimTransactionTimeout() { return claimTransactionTimeout; }
    public void setClaimTransactionTimeout(Duration claimTransactionTimeout) { this.claimTransactionTimeout = claimTransactionTimeout; }
    public Duration getConnectionAcquisitionTimeout() { return connectionAcquisitionTimeout; }
    public void setConnectionAcquisitionTimeout(Duration value) { this.connectionAcquisitionTimeout = value; }
    public Duration getConnectionEstablishmentTimeout() { return connectionEstablishmentTimeout; }
    public void setConnectionEstablishmentTimeout(Duration value) { this.connectionEstablishmentTimeout = value; }
    public Duration getMysqlLockWaitTimeout() { return mysqlLockWaitTimeout; }
    public void setMysqlLockWaitTimeout(Duration value) { this.mysqlLockWaitTimeout = value; }
    public Duration getStatementTimeout() { return statementTimeout; }
    public void setStatementTimeout(Duration statementTimeout) { this.statementTimeout = statementTimeout; }
    public Duration getSocketTimeout() { return socketTimeout; }
    public void setSocketTimeout(Duration socketTimeout) { this.socketTimeout = socketTimeout; }
}
