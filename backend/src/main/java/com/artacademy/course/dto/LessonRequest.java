package com.artacademy.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Thêm/sửa bài học. PUT thay thế toàn bộ các trường (gửi đủ cả những trường không đổi).
 * Các cờ để trống (null) được hiểu là false.
 */
public record LessonRequest(
        @NotBlank(message = "Tên bài học không được để trống")
        @Size(max = 200, message = "Tên bài học tối đa 200 ký tự")
        String title,

        @Size(max = 2000, message = "Mô tả bài học tối đa 2000 ký tự")
        String description,

        String videoUrl,

        @PositiveOrZero(message = "Thời lượng không được âm")
        Integer durationSeconds,

        String materialUrl,

        Boolean preview,
        Boolean required,
        Boolean hidden) {
}
