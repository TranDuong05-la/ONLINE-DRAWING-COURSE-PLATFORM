package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.PaymentEvent;

import java.util.Objects;
import java.util.Set;

/** Conservative classification of the two signed VNPay PAY result fields. */
public final class VnPayPaymentStatusClassifier {
    private static final Set<String> DOCUMENTED_DEFINITIVE_FAILURE_RESPONSES = Set.of(
            "09", "10", "11", "12", "13", "24", "51", "65", "75", "79");

    public enum Classification {
        CONFIRMED_SUCCESS,
        DEFINITIVE_FAILURE,
        SUSPICIOUS_DEBIT_SUCCESS,
        UNKNOWN_OR_CONTRADICTORY
    }

    public record Result(Classification classification, PaymentEvent.Outcome eventOutcome) {
        public Result {
            Objects.requireNonNull(classification);
            Objects.requireNonNull(eventOutcome);
        }
    }

    /**
     * Success remains the strict 00/00 pair. The failure allowlist is deliberately narrower
     * than the provider's response-code table: response codes documented as failed must also
     * agree with transaction status 02 (transaction error). All other combinations fail closed.
     */
    public Result classify(String responseCode, String transactionStatus) {
        if (!isTwoDigitCode(responseCode) || !isTwoDigitCode(transactionStatus))
            return unknown();

        if ("07".equals(responseCode) || "07".equals(transactionStatus)) {
            return new Result(Classification.SUSPICIOUS_DEBIT_SUCCESS, PaymentEvent.Outcome.UNKNOWN);
        }
        if ("00".equals(responseCode) && "00".equals(transactionStatus)) {
            return new Result(Classification.CONFIRMED_SUCCESS, PaymentEvent.Outcome.SUCCEEDED);
        }
        if (DOCUMENTED_DEFINITIVE_FAILURE_RESPONSES.contains(responseCode)
                && "02".equals(transactionStatus)) {
            return new Result(Classification.DEFINITIVE_FAILURE, PaymentEvent.Outcome.FAILED);
        }
        return unknown();
    }

    private static boolean isTwoDigitCode(String value) {
        return value != null && value.matches("[0-9]{2}");
    }

    private static Result unknown() {
        return new Result(Classification.UNKNOWN_OR_CONTRADICTORY, PaymentEvent.Outcome.UNKNOWN);
    }
}
