package com.artacademy.bundle.dto;

import com.artacademy.course.dto.CourseSummaryDto;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Combo ở màn admin.
 * visibleToPublic = đang bật VÀ mọi khóa đều PUBLISHED (nếu không thì học viên không thấy combo).
 * warnings giải thích vì sao combo đang bị ẩn đi hoặc giá không còn hợp lý.
 */
public record AdminBundleDto(
        Long id,
        String code,
        String title,
        String description,
        long price,
        boolean active,
        long originalTotal,
        long savings,
        boolean visibleToPublic,
        List<String> warnings,
        List<CourseSummaryDto> courses,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
