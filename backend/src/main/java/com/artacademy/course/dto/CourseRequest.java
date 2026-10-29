package com.artacademy.course.dto;

import com.artacademy.course.CourseLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Body của POST/PUT /api/admin/courses. Trạng thái không nằm ở đây (đổi bằng PATCH .../status). */
public record CourseRequest(
        @Size(max = 50, message = "Mã khóa học tối đa 50 ký tự")
        @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "Mã khóa học chỉ gồm chữ, số, dấu - và _")
        String code,

        @NotBlank(message = "Tên khóa học không được để trống")
        @Size(max = 200, message = "Tên khóa học tối đa 200 ký tự")
        String title,

        @Size(max = 500, message = "Mô tả ngắn tối đa 500 ký tự")
        String shortDescription,

        @Size(max = 20000, message = "Mô tả chi tiết tối đa 20000 ký tự")
        String description,

        String thumbnailUrl,

        @NotNull(message = "Giá gốc là bắt buộc")
        @PositiveOrZero(message = "Giá gốc không được âm")
        Long price,

        @Positive(message = "Giá giảm phải lớn hơn 0")
        Long discountPrice,

        @NotNull(message = "Vui lòng chọn cấp độ")
        CourseLevel level) {
}
