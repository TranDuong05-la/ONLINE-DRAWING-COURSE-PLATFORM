package com.artacademy.payment.vnpay;

import java.util.Map;

@FunctionalInterface
interface VnPayQueryDrTransport {
    Map<String, String> query(Map<String, String> request);
}
