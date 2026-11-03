package com.artacademy.bundle.dto;

import com.artacademy.course.dto.CourseSummaryDto;
import java.util.List;

/**
 * Combo ở API public.
 * originalTotal = tổng giá thực bán nếu mua lẻ từng khóa; savings = originalTotal - price.
 */
public record BundleDto(
        Long id,
        String code,
        String title,
        String description,
        long price,
        long originalTotal,
        long savings,
        List<CourseSummaryDto> courses) {
}
