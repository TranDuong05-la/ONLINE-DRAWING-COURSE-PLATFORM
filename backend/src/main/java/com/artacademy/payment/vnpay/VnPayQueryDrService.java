package com.artacademy.payment.vnpay;

import com.drawingcourse.backend.entity.PaymentAttempt;
import com.drawingcourse.backend.repository.PaymentAttemptRepository;
import com.drawingcourse.backend.service.PaymentPersistenceMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Local-demo status refresh; only a signed QueryDR success is passed to durable reconciliation. */
public class VnPayQueryDrService {
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNPAY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(VN_ZONE);
    private static final String PROVIDER = "vnpay";
    private final VnPayProperties properties;
    private final PaymentAttemptRepository attempts;
    private final VnPayQueryDrTransport transport;
    private final VnPayQueryDrVerifier verifier;
    private final VnPayIpnReconciliationService reconciliation;
    private final Clock clock;
    private final PaymentPersistenceMapper mapper = new PaymentPersistenceMapper();

    VnPayQueryDrService(VnPayProperties properties, PaymentAttemptRepository attempts,
            VnPayQueryDrTransport transport, VnPayQueryDrVerifier verifier,
            VnPayIpnReconciliationService reconciliation, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.attempts = Objects.requireNonNull(attempts);
        this.transport = Objects.requireNonNull(transport);
        this.verifier = Objects.requireNonNull(verifier);
        this.reconciliation = Objects.requireNonNull(reconciliation);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Reference is an untrusted locator only; all money, route and Order data come from persistence. */
    public Result refreshAfterBrowserReturn(String merchantReference) {
        properties.validateReady();
        if (!properties.getQueryDr().isEnabled())
            throw new IllegalStateException("VNPay local QueryDR mode is disabled");
        if (merchantReference == null || !merchantReference.matches("[A-Za-z0-9_-]{1,100}"))
            return Result.notConfirmed();
        var row = attempts.findByProviderAndMerchantReference(PROVIDER, merchantReference).orElse(null);
        if (row == null) return Result.notConfirmed();
        PaymentAttempt attempt = mapper.toDomain(row);
        if (!PROVIDER.equals(attempt.provider()) || !merchantReference.equals(attempt.merchantReference())
                || attempt.orderId() == null || attempt.orderId() <= 0 || attempt.expectedAmountVnd() <= 0
                || !"VND".equals(attempt.currency()))
            throw new IllegalStateException("Persisted payment attempt cannot be queried");

        String orderInfo = "Payment for order " + attempt.orderId();
        Map<String, String> request = buildRequest(properties, attempt, orderInfo, clock.instant());
        Map<String, String> response = transport.query(request);
        VnPayQueryDrVerifier.VerifiedResponse verified = verifier.verify(response, merchantReference, orderInfo);
        if (!verified.paymentSuccess()) return Result.notConfirmed();
        IpnReconciliationResult result = reconciliation.reconcileQueryDr(verified.callback());
        return new Result(true, result.classification(), result.paymentAttemptId(), result.orderId());
    }

    static Map<String, String> buildRequest(VnPayProperties properties, PaymentAttempt attempt,
                                            String orderInfo, Instant now) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> request = new LinkedHashMap<>();
        request.put("vnp_RequestId", requestId);
        request.put("vnp_Version", "2.1.0");
        request.put("vnp_Command", "querydr");
        request.put("vnp_TmnCode", properties.getTmnCode());
        request.put("vnp_TxnRef", attempt.merchantReference());
        request.put("vnp_TransactionDate", VNPAY_DATE.format(attempt.createdAt()));
        request.put("vnp_CreateDate", VNPAY_DATE.format(now));
        request.put("vnp_IpAddr", properties.getQueryDr().getIpAddress());
        request.put("vnp_OrderInfo", orderInfo);
        request.put("vnp_SecureHash", VnPaySignature.hmacSha512Hex(properties.getSecretKey(), requestCanonical(request)));
        return Map.copyOf(request);
    }

    static String requestCanonical(Map<String, String> request) {
        return String.join("|", request.get("vnp_RequestId"), request.get("vnp_Version"),
                request.get("vnp_Command"), request.get("vnp_TmnCode"), request.get("vnp_TxnRef"),
                request.get("vnp_TransactionDate"), request.get("vnp_CreateDate"), request.get("vnp_IpAddr"),
                request.get("vnp_OrderInfo"));
    }

    public record Result(boolean verifiedPaymentSuccess, IpnClassification reconciliationClassification,
                         Integer paymentAttemptId, Integer orderId) {
        static Result notConfirmed() { return new Result(false, null, null, null); }
    }
}
