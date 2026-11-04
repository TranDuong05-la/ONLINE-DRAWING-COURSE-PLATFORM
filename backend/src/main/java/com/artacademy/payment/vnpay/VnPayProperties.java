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
    private boolean allowLocalHttpReturnUrl;
    private QueryDr queryDr = new QueryDr();
    private Ipn ipn = new Ipn();
    private String apiVersion = "2.1.0";
    private String command = "pay";

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
        if (returnUrl != null && returnUrl.regionMatches(true, 0, "http://", 0, 7)) {
            if (!allowLocalHttpReturnUrl) throw new IllegalStateException("VNPay return URL must be an absolute HTTPS URL");
            requireLocalHttpReturn(returnUrl);
        } else {
            requireHttps(returnUrl, "return URL");
        }
        if (queryDr.enabled) {
            if (ipn.enabled) throw new IllegalStateException("VNPay IPN and local QueryDR modes are mutually exclusive");
            if (ipnUrl != null && !ipnUrl.isBlank())
                throw new IllegalStateException("Clear VNPay IPN URL when local QueryDR mode is enabled");
            if (!validIpAddress(queryDr.ipAddress))
                throw new IllegalStateException("VNPay QueryDR server IP address is required");
        } else {
            requireHttps(ipnUrl, "IPN URL");
        }
        if (!"2.1.0".equals(apiVersion) || !"pay".equals(command))
            throw new IllegalStateException("Unsupported VNPay API version or command");
    }

    private static void requireLocalHttpReturn(String value) {
        try {
            var uri = java.net.URI.create(value);
            boolean allowedHost = "localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost()) || "10.0.2.2".equals(uri.getHost());
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !allowedHost
                    || uri.getPort() != 8080 || !"/api/payments/vnpay/return".equals(uri.getPath())
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null)
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("VNPay local return URL must be the loopback return endpoint on port 8080");
        }
    }

    private static void requireHttps(String value, String label) {
        try {
            var uri = java.net.URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("VNPay " + label + " must be an absolute HTTPS URL");
        }
    }

    private static boolean validIpAddress(String value) {
        if (value == null || !value.matches("[0-9a-fA-F:.]{2,45}")) return false;
        try {
            var address = java.net.InetAddress.getByName(value);
            return address instanceof java.net.Inet4Address || address instanceof java.net.Inet6Address;
        } catch (java.net.UnknownHostException ex) {
            return false;
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
    public boolean isAllowLocalHttpReturnUrl() { return allowLocalHttpReturnUrl; }
    public void setAllowLocalHttpReturnUrl(boolean allowLocalHttpReturnUrl) { this.allowLocalHttpReturnUrl = allowLocalHttpReturnUrl; }
    public QueryDr getQueryDr() { return queryDr; }
    public void setQueryDr(QueryDr queryDr) { this.queryDr = queryDr; }
    public Ipn getIpn() { return ipn; }
    public void setIpn(Ipn ipn) { this.ipn = ipn; }
    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public static class QueryDr {
        private boolean enabled;
        private String ipAddress = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getIpAddress() { return ipAddress; }
        public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    }

    public static class Ipn {
        private boolean enabled;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
