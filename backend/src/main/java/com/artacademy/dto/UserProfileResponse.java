package com.artacademy.dto;

import com.artacademy.entity.UserProfile;
import com.artacademy.enums.AgeGroup;
import com.artacademy.enums.ProfileType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileResponse {

    private Integer id;
    private Integer userId;
    private ProfileType profileType;
    private String displayName;
    private String avatarUrl;
    private AgeGroup ageGroup;
    private String bio;
    private String signatureImageUrl;
    private Boolean isApproved;
    private LocalDateTime createdAt;

    public static UserProfileResponse fromEntity(UserProfile profile) {
        if (profile == null) return null;
        return UserProfileResponse.builder()
                .id(profile.getId())
                .userId(profile.getUser() != null ? profile.getUser().getId() : null)
                .profileType(profile.getProfileType())
                .displayName(profile.getDisplayName())
                .avatarUrl(profile.getAvatarUrl())
                .ageGroup(profile.getAgeGroup())
                .bio(profile.getBio())
                .signatureImageUrl(profile.getSignatureImageUrl())
                .isApproved(profile.getIsApproved())
                .createdAt(profile.getCreatedAt())
                .build();
    }
}
