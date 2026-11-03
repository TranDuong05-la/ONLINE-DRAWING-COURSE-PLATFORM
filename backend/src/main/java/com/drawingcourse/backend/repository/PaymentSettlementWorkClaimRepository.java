package com.drawingcourse.backend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import com.drawingcourse.backend.service.settlement.SettlementTimeoutProperties;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Native locking/update primitives for the short settlement-work claim transaction. */
@Repository
public class PaymentSettlementWorkClaimRepository {
    private final JdbcTemplate jdbc;

    public PaymentSettlementWorkClaimRepository(DataSource dataSource, SettlementTimeoutProperties timeoutProperties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(Math.toIntExact(timeoutProperties.getStatementTimeout().toSeconds()));
    }

    public Instant databaseUtcNow() {
        String product = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
        if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("mysql")) {
            LocalDateTime utc = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
            return utc.toInstant(ZoneOffset.UTC);
        }
        return jdbc.query("SELECT CURRENT_TIMESTAMP", rs -> {
            if (!rs.next()) throw new IllegalStateException("Database did not return its current time");
            Object value = rs.getObject(1);
            if (value instanceof OffsetDateTime offset) return offset.toInstant();
            if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
            throw new IllegalStateException("Unsupported database current-time type: " + value.getClass().getName());
        });
    }

    public Optional<Candidate> lockReady(Instant now) { return lockDue("READY", now); }
    public Optional<Candidate> lockRetryWait(Instant now) { return lockDue("RETRY_WAIT", now); }
    public Optional<Candidate> lockExpiredLease(Instant now) { return lockLease(now, true); }
    public Optional<Candidate> lockExhaustedLease(Instant now) { return lockLease(now, false); }

    private Optional<Candidate> lockDue(String state, Instant now) {
        // State is selected from fixed internal call sites, never caller input.
        List<Candidate> rows = jdbc.query("""
                SELECT id,payment_event_id,attempt_count,retry_generation FROM payment_settlement_work_items
                WHERE state=? AND available_at<=? AND attempt_count<8
                ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Candidate(rs.getInt(1), rs.getInt(2), rs.getShort(3), rs.getLong(4)), state, ts(now));
        return rows.stream().findFirst();
    }

    /** Expired leases are split by attempt limit to keep automatic reclaim bounded. */
    private Optional<Candidate> lockLease(Instant now, boolean belowLimit) {
        String attemptPredicate = belowLimit ? "attempt_count < 8" : "attempt_count >= 8";
        List<Candidate> rows = jdbc.query("""
                SELECT id,payment_event_id,attempt_count,retry_generation FROM payment_settlement_work_items
                WHERE state='LEASED' AND lease_until<=? AND """ + " " + attemptPredicate +
                " ORDER BY lease_until,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (rs, row) -> new Candidate(rs.getInt(1), rs.getInt(2), rs.getShort(3), rs.getLong(4)), ts(now));
        return rows.stream().findFirst();
    }

    public int claim(Candidate item, String token, String owner, Instant now, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE payment_settlement_work_items
                SET state='LEASED', attempt_count=attempt_count+1, available_at=NULL, lease_token=?, lease_owner=?,
                    lease_until=?, last_claimed_at=?, updated_at=?
                WHERE id=? AND attempt_count=? AND retry_generation=?
                    AND ((state IN ('READY','RETRY_WAIT') AND available_at<=?) OR (state='LEASED' AND lease_until<=?))
                """, token, owner, ts(leaseUntil), ts(now), ts(now), item.id(), item.attemptCount(),
                item.retryGeneration(), ts(now), ts(now));
    }

    public int exhaustExpired(Candidate item, Instant now) {
        return jdbc.update("""
                UPDATE payment_settlement_work_items SET state='MANUAL_REVIEW', available_at=NULL,
                    lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_failure_code='LEASE_EXPIRED',updated_at=?
                WHERE id=? AND state='LEASED' AND attempt_count>=8 AND lease_until<=?
                """, ts(now), item.id(), ts(now));
    }

    /** Fenced transition; a stale or expired token changes zero rows. */
    public int scheduleRetry(int id, String token, String code, Instant now, Instant dueAt) {
        requireTransaction();
        if (!lockFencedRow(id, token, "attempt_count BETWEEN 1 AND 7")) return 0;
        String leaseStillValid = leaseStillValidSql();
        return jdbc.update("""
                UPDATE payment_settlement_work_items SET state='RETRY_WAIT',available_at=?,lease_token=NULL,
                    lease_owner=NULL,lease_until=NULL,last_failure_code=?,updated_at=?
                WHERE id=? AND state='LEASED' AND lease_token=? """ + " AND " + leaseStillValid +
                " AND attempt_count BETWEEN 1 AND 7",
                ts(dueAt), code, ts(now), id, token);
    }

    /** Fenced final claim-budget transition; does not execute settlement or alter an Order. */
    public int exhaustAfterFailure(int id, String token, String code, Instant now) {
        requireTransaction();
        if (!lockFencedRow(id, token, "attempt_count=8")) return 0;
        String leaseStillValid = leaseStillValidSql();
        return jdbc.update("""
                UPDATE payment_settlement_work_items SET state='MANUAL_REVIEW',available_at=NULL,
                    lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_failure_code=?,updated_at=?
                WHERE id=? AND state='LEASED' AND lease_token=? """ + " AND " + leaseStillValid +
                " AND attempt_count=8",
                code, ts(now), id, token);
    }

    /** Completes a successfully settled claim. This conditional UPDATE is the final application-issued write. */
    public int completeSettlement(int id, String token) {
        requireTransaction();
        // Finish any wait for the row lock before the UPDATE statement samples its database clock.
        if (!lockFencedRow(id, token, "1=1")) return 0;
        String leaseStillValid = leaseStillValidSql();
        return jdbc.update("""
                UPDATE payment_settlement_work_items SET state='COMPLETED',available_at=NULL,
                    lease_token=NULL,lease_owner=NULL,lease_until=NULL,completed_at=%s,updated_at=%s
                WHERE id=? AND state='LEASED' AND lease_token=? AND %s
                """.formatted(currentUtcSql(), currentUtcSql(), leaseStillValid), id, token);
    }

    /** Fails closed into the existing manual-review state for terminal business ineligibility. */
    public int requireManualReview(int id, String token, String code) {
        requireTransaction();
        if (!java.util.Set.of("EVENT_NOT_ELIGIBLE", "ORDER_NOT_ELIGIBLE", "SETTLEMENT_CONFLICT",
                "PARTICIPANT_REJECTED", "UNCLASSIFIED_FAILURE").contains(code))
            throw new IllegalArgumentException("Unsupported manual-review failure code");
        if (!lockFencedRow(id, token, "1=1")) return 0;
        String leaseStillValid = leaseStillValidSql();
        return jdbc.update("""
                UPDATE payment_settlement_work_items SET state='MANUAL_REVIEW',available_at=NULL,
                    lease_token=NULL,lease_owner=NULL,lease_until=NULL,last_failure_code=?,updated_at=%s
                WHERE id=? AND state='LEASED' AND lease_token=? AND %s
                """.formatted(currentUtcSql(), leaseStillValid), code, id, token);
    }

    /** A fresh current read used only after a failed execution transaction has fully returned. */
    public boolean ownsUnexpiredLease(int id, String token) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_settlement_work_items
                WHERE id=? AND state='LEASED' AND lease_token=? AND %s
                """.formatted(leaseStillValidSql()), Integer.class, id, token);
        return count != null && count == 1;
    }

    private String currentUtcSql() {
        String product = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
        if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("mysql"))
            return "UTC_TIMESTAMP(6)";
        if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("h2"))
            return "DATEADD('MINUTE', -EXTRACT(TIMEZONE_MINUTE FROM CURRENT_TIMESTAMP), " +
                    "DATEADD('HOUR', -EXTRACT(TIMEZONE_HOUR FROM CURRENT_TIMESTAMP), " +
                    "CAST(CURRENT_TIMESTAMP AS TIMESTAMP(6))))";
        throw new IllegalStateException("Settlement work completion is unsupported for database product: " + product);
    }

    /** Acquire the work-row lock before the fenced UPDATE so lock waiting precedes its database-clock test. */
    private boolean lockFencedRow(int id, String token, String attemptPredicate) {
        List<Integer> rows = jdbc.query("""
                SELECT id FROM payment_settlement_work_items
                WHERE id=? AND state='LEASED' AND lease_token=? """ + " AND " + attemptPredicate + " FOR UPDATE",
                (rs, row) -> rs.getInt(1), id, token);
        return !rows.isEmpty();
    }

    private String leaseStillValidSql() {
        String product = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
        if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("mysql"))
            return "lease_until > UTC_TIMESTAMP(6)";
        if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("h2"))
            return "lease_until > DATEADD('MINUTE', -EXTRACT(TIMEZONE_MINUTE FROM CURRENT_TIMESTAMP), " +
                    "DATEADD('HOUR', -EXTRACT(TIMEZONE_HOUR FROM CURRENT_TIMESTAMP), CAST(CURRENT_TIMESTAMP AS TIMESTAMP(6))))";
        throw new IllegalStateException("Lease fencing is unsupported for database product: " + product);
    }

    private static void requireTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Fenced work mutation requires one active local transaction");
    }

    private static LocalDateTime ts(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    public record Candidate(int id, int paymentEventId, short attemptCount, long retryGeneration) { }
}
