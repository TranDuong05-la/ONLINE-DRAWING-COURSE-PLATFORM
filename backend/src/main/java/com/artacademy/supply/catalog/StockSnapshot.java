package com.artacademy.supply.catalog;

public record StockSnapshot(int onHand, int reserved) {
    public int available() {
        return Math.max(0, onHand - reserved);
    }
}
