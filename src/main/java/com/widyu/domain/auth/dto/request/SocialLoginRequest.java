package com.widyu.domain.auth.dto.request;

import lombok.Builder;

@Builder
public record SocialLoginRequest(
        String accessToken,
        String authorizationCode,
        String refreshToken,
        AppleProfile profile
) {
    public record AppleProfile(
            String email,
            String name
    ) {}
    
    public static SocialLoginRequest of(String accessToken) {
        return SocialLoginRequest.builder()
                .accessToken(accessToken)
                .build();
    }
}
