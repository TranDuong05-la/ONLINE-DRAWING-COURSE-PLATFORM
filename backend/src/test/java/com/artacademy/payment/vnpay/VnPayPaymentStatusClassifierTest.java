package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.PaymentEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VnPayPaymentStatusClassifierTest {
    private final VnPayPaymentStatusClassifier classifier = new VnPayPaymentStatusClassifier();

    @Test void acceptsOnlyStrictSuccessPair() {
        var result = classifier.classify("00", "00");
        assertEquals(VnPayPaymentStatusClassifier.Classification.CONFIRMED_SUCCESS, result.classification());
        assertEquals(PaymentEvent.Outcome.SUCCEEDED, result.eventOutcome());
    }

    @Test void suspiciousDebitSuccessNeverBecomesFailureOrSuccess() {
        for (String status : new String[] {"00", "01", "02", "04", "07"}) {
            var result = classifier.classify("07", status);
            assertEquals(VnPayPaymentStatusClassifier.Classification.SUSPICIOUS_DEBIT_SUCCESS,
                    result.classification(), "response=07, status=" + status);
            assertEquals(PaymentEvent.Outcome.UNKNOWN, result.eventOutcome());
        }
        var statusOnlySuspicious = classifier.classify("00", "07");
        assertEquals(VnPayPaymentStatusClassifier.Classification.SUSPICIOUS_DEBIT_SUCCESS,
                statusOnlySuspicious.classification());
        assertEquals(PaymentEvent.Outcome.UNKNOWN, statusOnlySuspicious.eventOutcome());
    }

    @Test void allowlistsDocumentedFailureResponsesOnlyWithTransactionErrorStatus() {
        for (String response : new String[] {"09", "10", "11", "12", "13", "24", "51", "65", "75", "79"}) {
            var result = classifier.classify(response, "02");
            assertEquals(VnPayPaymentStatusClassifier.Classification.DEFINITIVE_FAILURE,
                    result.classification(), "response=" + response);
            assertEquals(PaymentEvent.Outcome.FAILED, result.eventOutcome());
        }
        assertUnknown("24", "00");
        assertUnknown("24", "01");
        assertUnknown("24", "04");
        assertUnknown("00", "02");
        assertUnknown("99", "02");
    }

    @Test void unsupportedAndMalformedCodesRemainUnknown() {
        assertUnknown("88", "02");
        assertUnknown("xx", "02");
        assertUnknown("00", null);
        assertUnknown("", "00");
    }

    private void assertUnknown(String response, String status) {
        var result = classifier.classify(response, status);
        assertEquals(VnPayPaymentStatusClassifier.Classification.UNKNOWN_OR_CONTRADICTORY,
                result.classification());
        assertEquals(PaymentEvent.Outcome.UNKNOWN, result.eventOutcome());
    }
}
