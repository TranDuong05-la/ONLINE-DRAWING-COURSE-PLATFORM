package com.artacademy.supply.api;

public record ReservationView(Long productId, String sku, String productName, int quantity, boolean gift,
        String status) {
}
