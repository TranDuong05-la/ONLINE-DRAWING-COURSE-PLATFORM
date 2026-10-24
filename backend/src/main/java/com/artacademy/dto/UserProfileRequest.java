package com.artacademy.dto;

import com.artacademy.enums.AgeGroup;
import com.artacademy.enums.ProfileType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileRequest {

    @NotNull(message = "Profile type is required")
    private ProfileType profileType;

    @NotBlank(message = "Display name is required")
    @Size(max = 100, message = "Display name must not exceed 100 characters")
    private String displayName;

    @Size(max = 255, message = "Avatar URL must not exceed 255 characters")
    private String avatarUrl;

    private AgeGroup ageGroup;

    private String bio;

    @Size(max = 255, message = "Signature image URL must not exceed 255 characters")
    private String signatureImageUrl;
}
