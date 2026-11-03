package com.artacademy.course.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Danh sách id theo thứ tự mới. Phải gửi ĐỦ tất cả id (kể cả mục đang ẩn), không thiếu, không trùng. */
public record ReorderRequest(@NotNull(message = "Thiếu danh sách id") List<Long> ids) {
}
