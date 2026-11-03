package com.artacademy.payment.vnpay;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentEventInsertRaceDetectionTest {
    @Test
    void recognizesOnlyV5EventIdentityDuplicate() {
        assertTrue(VnPayIpnReconciliationService.isExactPaymentEventIdentityDuplicate(
                failure("Duplicate entry for key 'payment_events.uk_payment_event'", "23000", 1062)));
    }

    @Test
    void doesNotTreatAnotherUniqueConstraintAsAnEventReplay() {
        assertFalse(VnPayIpnReconciliationService.isExactPaymentEventIdentityDuplicate(
                failure("Duplicate entry for key 'uk_payment_event_conflict'", "23000", 1062)));
    }

    @Test
    void doesNotTreatForeignKeyOrCheckFailuresAsAnEventReplay() {
        assertFalse(VnPayIpnReconciliationService.isExactPaymentEventIdentityDuplicate(
                failure("fk_payment_event_attempt", "23000", 1452)));
        assertFalse(VnPayIpnReconciliationService.isExactPaymentEventIdentityDuplicate(
                failure("ck_payment_event_amount", "HY000", 3819)));
    }

    @Test
    void recognizesH2UniqueViolationOnlyWhenItNamesTheV5IdentityConstraint() {
        var exact = new DataIntegrityViolationException("unique failure", new SQLException(
                "Unique index or primary key violation: \"UK_PAYMENT_EVENT\"", "23505", 23505));
        var unrelated = new DataIntegrityViolationException("unique failure", new SQLException(
                "Unique index or primary key violation: \"UK_PAYMENT_EVENT_ID_ATTEMPT_ORDER\"", "23505", 23505));
        assertTrue(VnPayIpnReconciliationService.isExactPaymentEventIdentityDuplicate(exact));
        assertFalse(VnPayIpnReconciliationService.isExactPaymentEventIdentityDuplicate(unrelated));
    }

    private static DataIntegrityViolationException failure(String message, String state, int code) {
        return new DataIntegrityViolationException("translated database failure", new SQLException(message, state, code));
    }
}
