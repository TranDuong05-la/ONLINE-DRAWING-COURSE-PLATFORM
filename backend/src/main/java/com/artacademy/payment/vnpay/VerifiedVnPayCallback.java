package com.artacademy.payment.vnpay;

import java.util.Map;
import java.util.Objects;

/** Immutable, verified callback value. Package-private construction is reserved for the verifier. */
public final class VerifiedVnPayCallback {
    private final Map<String, String> signedParameters;
    private final String signature;

    VerifiedVnPayCallback(Map<String, String> signedParameters, String signature) {
        this.signedParameters = Map.copyOf(Objects.requireNonNull(signedParameters));
        this.signature = Objects.requireNonNull(signature);
    }

    public Map<String, String> signedParameters() { return signedParameters; }
    public String signature() { return signature; }
    public String merchantReference() { return signedParameters.get("vnp_TxnRef"); }
    public String amountMinorUnits() { return signedParameters.get("vnp_Amount"); }
    public String bankCode() { return signedParameters.get("vnp_BankCode"); }
    public String orderInfo() { return signedParameters.get("vnp_OrderInfo"); }
    public String transactionReference() { return signedParameters.get("vnp_TransactionNo"); }
    public String responseCode() { return signedParameters.get("vnp_ResponseCode"); }
    public String transactionStatus() { return signedParameters.get("vnp_TransactionStatus"); }
    public String providerPaymentDate() { return signedParameters.get("vnp_PayDate"); }
}
