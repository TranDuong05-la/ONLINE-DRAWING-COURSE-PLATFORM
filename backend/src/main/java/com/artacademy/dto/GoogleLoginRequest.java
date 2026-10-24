package com.artacademy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoogleLoginRequest {

    private String idToken;
    private String code;
    private String redirectUri;
    private String email;
    private String fullName;
    private String avatarUrl;
}
