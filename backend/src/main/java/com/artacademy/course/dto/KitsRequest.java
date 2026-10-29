package com.artacademy.course.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Body của PUT /api/admin/courses/{id}/kits: toàn bộ danh sách quà mới (gửi danh sách rỗng để bỏ hết quà). */
public record KitsRequest(@NotNull(message = "Thiếu danh sách quà tặng") @Valid List<Item> items) {

    public record Item(
            @NotNull(message = "Thiếu sản phẩm") Long productId,

            @NotNull(message = "Thiếu số lượng")
            @Min(value = 1, message = "Số lượng quà phải từ 1 đến 99")
            @Max(value = 99, message = "Số lượng quà phải từ 1 đến 99")
            Integer quantity) {
    }
}
