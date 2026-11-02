package com.artacademy.course.dto;

/** Một món quà tặng kèm: sản phẩm + số lượng. */
public record KitDto(Long productId, String sku, String name, String thumbnailUrl, int quantity) {
}
