package com.drawingcourse.backend.dto;

import com.drawingcourse.backend.entity.CartItemType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Prices and user identity are deliberately absent from this request. */
public record CartItemRequest(
        @NotNull CartItemType itemType,
        @NotNull @Positive Integer itemId,
        @NotNull @Positive Integer quantity) {
    @AssertTrue(message = "Courses require quantity 1")
    public boolean isCourseQuantityValid() {
        return itemType != CartItemType.COURSE || quantity == null || quantity == 1;
    }
}
