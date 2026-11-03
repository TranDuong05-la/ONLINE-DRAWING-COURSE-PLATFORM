package com.artacademy.dto;

import com.artacademy.enums.Role;

import java.time.LocalDateTime;

public record RoleHistoryResponse(Integer id, Integer userId, String userEmail, String userFullName,
                                  Integer changedById, String changedByEmail, String changedByFullName,
                                  Role oldRole, Role newRole, String reason, LocalDateTime changedAt) {
}
