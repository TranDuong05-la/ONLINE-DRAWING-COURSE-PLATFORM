package com.artacademy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateUserStatusRequest {

    private Boolean isActive;
    private String status; // Can be ACTIVE, INACTIVE, LOCKED
    private String reason;

    public Boolean resolveIsActive() {
        if (isActive != null) {
            return isActive;
        }
        if (status != null) {
            String trimmed = status.trim().toUpperCase();
            if ("ACTIVE".equals(trimmed)) {
                return true;
            } else if ("INACTIVE".equals(trimmed) || "LOCKED".equals(trimmed)) {
                return false;
            }
        }
        return null;
    }
}
