package com.drawingcourse.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AdminVoucherStatusRequest(
        @NotNull Boolean active,
        @NotNull @Positive Long expectedDefinitionVersion) {
}
