package com.artacademy.course.dto;

/**
 * Một món quà tặng kèm: sản phẩm + số lượng.
 * available = false khi sản phẩm đã ngừng bán hoặc không còn trong danh mục của E (admin thấy để gỡ hoặc thay; học viên không thấy).
 */
public record KitDto(Integer productId, String sku, String name, String thumbnailUrl, int quantity, boolean available) {
}