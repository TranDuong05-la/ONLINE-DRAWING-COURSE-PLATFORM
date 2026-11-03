package com.artacademy.bundle.dto;

import com.artacademy.course.dto.CourseSummaryDto;
import java.util.List;

/**
 * Combo ở API public.
 * originalTotal = tổng giá thực bán nếu mua lẻ từng khóa; savings = originalTotal - price.
 * kitsUnavailable = có khóa trong combo thiếu quà tặng kèm: combo vẫn hiển thị nhãn "Tạm thiếu quà" nhưng không cho thêm vào giỏ.
 */
public record BundleDto(
        Integer id,
        String code,
        String title,
        String description,
        long price,
        long originalTotal,
        long savings,
        boolean kitsUnavailable,
        List<CourseSummaryDto> courses) {
}
