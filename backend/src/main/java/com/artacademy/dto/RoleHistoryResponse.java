package com.artacademy.dto;

import com.artacademy.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleHistoryResponse {

    private Integer id;
    private Integer userId;
    private String userEmail;
    private String userFullName;
    private Integer changedById;
    private String changedByEmail;
    private String changedByFullName;
    private Role oldRole;
    private Role newRole;
    private String reason;
    private LocalDateTime changedAt;
}
