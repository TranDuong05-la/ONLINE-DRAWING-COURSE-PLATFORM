package com.artacademy.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Thêm/sửa module. `hidden` = true để ẩn (không có chức năng xóa). */
public record ModuleRequest(
        @NotBlank(message = "Tên module không được để trống")
        @Size(max = 200, message = "Tên module tối đa 200 ký tự")
        String title,

        Boolean hidden) {
}
