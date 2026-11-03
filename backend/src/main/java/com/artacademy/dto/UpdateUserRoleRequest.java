package com.artacademy.dto;

import com.artacademy.enums.Role;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateUserRoleRequest(@NotNull Role role, @Size(max = 255) String reason) {
}
