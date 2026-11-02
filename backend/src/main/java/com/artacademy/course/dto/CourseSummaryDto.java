package com.artacademy.course.dto;

import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseStatus;
import java.time.LocalDateTime;

/**
 * Thông tin thẻ khóa học ở danh sách (dùng cho cả học viên và admin).
 * Giá là VND. effectivePrice = giá giảm nếu có, không thì giá gốc.
 * hasKits = khóa có dụng cụ tặng kèm (app hiện nhãn "Tặng kèm dụng cụ").
 * lessonCount/totalDurationSeconds chỉ tính các bài đang hiển thị.
 */
public record CourseSummaryDto(
        Long id,
        String code,
        String slug,
        String title,
        String shortDescription,
        String thumbnailUrl,
        long price,
        Long discountPrice,
        long effectivePrice,
        CourseLevel level,
        CourseStatus status,
        boolean hasKits,
        long lessonCount,
        long totalDurationSeconds,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
