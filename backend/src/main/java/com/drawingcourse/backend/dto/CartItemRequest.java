package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.entity.CartItemType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** itemId is an opaque transport value, to be parsed by the catalog adapter. */
public record CartItemRequest(
        @NotNull CartItemType itemType,
        @NotBlank String itemId,
        @NotNull @Positive Integer quantity) {
    @AssertTrue(message = "Courses require quantity 1")
    public boolean isCourseQuantityValid() {
        return itemType != CartItemType.COURSE || quantity == null || quantity == 1;
    }
}
