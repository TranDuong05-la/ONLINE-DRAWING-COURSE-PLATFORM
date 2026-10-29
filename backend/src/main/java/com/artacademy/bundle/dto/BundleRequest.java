package com.artacademy.bundle.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body của POST/PUT /api/admin/bundles. */
public record BundleRequest(
        @Size(max = 50, message = "Mã combo tối đa 50 ký tự")
        @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "Mã combo chỉ gồm chữ, số, dấu - và _")
        String code,

        @NotBlank(message = "Tên combo không được để trống")
        @Size(max = 200, message = "Tên combo tối đa 200 ký tự")
        String title,

        @Size(max = 2000, message = "Mô tả combo tối đa 2000 ký tự")
        String description,

        @NotNull(message = "Giá combo là bắt buộc")
        @Positive(message = "Giá combo phải lớn hơn 0")
        Long price,

        @NotNull(message = "Vui lòng chọn khóa học cho combo")
        @Size(min = 2, message = "Combo cần ít nhất 2 khóa học")
        List<@NotNull(message = "Id khóa học không hợp lệ") Long> courseIds,

        Boolean active) {
}
