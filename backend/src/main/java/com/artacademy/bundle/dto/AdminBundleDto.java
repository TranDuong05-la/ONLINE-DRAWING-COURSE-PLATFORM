package com.artacademy.bundle.dto;

import com.artacademy.course.dto.CourseSummaryDto;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Combo ở màn admin. definitionVersion: tăng khi đổi mã, tên, giá, danh sách hoặc thứ tự khóa (xem CourseBundle).
 * visibleToPublic = đang bật VÀ mọi khóa đều PUBLISHED (nếu không thì học viên không thấy combo).
 * warnings giải thích vì sao combo đang bị ẩn đi hoặc giá không còn hợp lý.
 */
public record AdminBundleDto(
        Integer id,
        String code,
        String title,
        String description,
        long price,
        int definitionVersion,
        boolean active,
        long originalTotal,
        long savings,
        boolean visibleToPublic,
        List<String> warnings,
        List<CourseSummaryDto> courses,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
