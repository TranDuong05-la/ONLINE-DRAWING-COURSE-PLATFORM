package com.artacademy.bundle.dto;

import jakarta.validation.constraints.NotNull;

public record BundleActiveRequest(@NotNull(message = "Thiếu giá trị active") Boolean active) {
}
