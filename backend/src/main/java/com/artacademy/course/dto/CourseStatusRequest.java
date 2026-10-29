package com.artacademy.course.dto;

import com.artacademy.course.CourseStatus;
import jakarta.validation.constraints.NotNull;

public record CourseStatusRequest(@NotNull(message = "Vui lòng chọn trạng thái") CourseStatus status) {
}
