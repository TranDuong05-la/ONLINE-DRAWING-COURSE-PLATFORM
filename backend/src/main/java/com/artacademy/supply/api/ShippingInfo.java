package com.artacademy.supply.api;

/**
 * Bản chụp địa chỉ tại lúc mua. D lấy từ địa chỉ của A (user_addresses) rồi ghép thành một dòng,
 * vd. "101 Kim Mã, Phường Kim Mã, Ba Đình, Hà Nội".
 */
public record ShippingInfo(String recipientName, String phone, String address) {
}
