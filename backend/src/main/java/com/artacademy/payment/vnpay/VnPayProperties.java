package com.artacademy.payment.vnpay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.annotation.PostConstruct;

/** Configuration values for the optional VNPay sandbox adapter. Never log this object. */
@ConfigurationProperties(prefix = "app.payment.vnpay")
public class VnPayProperties {
    private boolean enabled;
    private String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private String tmnCode = "";
    private String secretKey = "";
    private String returnUrl = "";
    private String ipnUrl = "";
    private String apiVersion = "2.1.0";
    private String command = "pay";
    /**
     * Chỉ dùng khi chạy trên máy cá nhân (VNPay không gọi IPN về localhost được): cho phép return/IPN URL dùng http
     * và bật trang /api/payments/vnpay/return xác nhận thanh toán bằng chính chữ ký + đối soát như IPN.
     */
    private boolean allowLocalHttpReturn;

    @PostConstruct
    void validateWhenEnabled() {
        if (enabled) validateReady();
    }

    public void validateReady() {
        if (!enabled) throw new IllegalStateException("VNPay integration is disabled");
        if (tmnCode == null || !tmnCode.matches("[A-Za-z0-9]{8}"))
            throw new IllegalStateException("VNPay TMN code is not configured");
        if (secretKey == null || secretKey.isBlank())
            throw new IllegalStateException("VNPay signing key is not configured");
        if (!"https://sandbox.vnpayment.vn/paymentv2/vpcpay.html".equals(paymentUrl))
            throw new IllegalStateException("VNPay payment URL must use the configured sandbox endpoint");
        requireHttps(returnUrl, "return URL", allowLocalHttpReturn);
        requireHttps(ipnUrl, "IPN URL", allowLocalHttpReturn);
        if (!"2.1.0".equals(apiVersion) || !"pay".equals(command))
            throw new IllegalStateException("Unsupported VNPay API version or command");
    }

    private static void requireHttps(String value, String label, boolean allowHttp) {
        try {
            var uri = java.net.URI.create(value);
            boolean schemeOk = "https".equalsIgnoreCase(uri.getScheme())
                    || allowHttp && "http".equalsIgnoreCase(uri.getScheme());
            if (!schemeOk || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("VNPay " + label + " must be an absolute "
                    + (allowHttp ? "HTTP(S)" : "HTTPS") + " URL");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPaymentUrl() { return paymentUrl; }
    public void setPaymentUrl(String paymentUrl) { this.paymentUrl = paymentUrl; }
    public String getTmnCode() { return tmnCode; }
    public void setTmnCode(String tmnCode) { this.tmnCode = tmnCode; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
    public String getIpnUrl() { return ipnUrl; }
    public void setIpnUrl(String ipnUrl) { this.ipnUrl = ipnUrl; }
    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public boolean isAllowLocalHttpReturn() { return allowLocalHttpReturn; }
    public void setAllowLocalHttpReturn(boolean allowLocalHttpReturn) { this.allowLocalHttpReturn = allowLocalHttpReturn; }
}
