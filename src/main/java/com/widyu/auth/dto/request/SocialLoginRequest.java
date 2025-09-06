package com.widyu.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record SocialLoginRequest(
        @JsonProperty("accessToken")
        String accessToken,
        
        @JsonProperty("authorizationCode") 
        String authorizationCode,
        
        @JsonProperty("profile")
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
    
    public static SocialLoginRequest ofApple(String authorizationCode, AppleProfile profile) {
        return SocialLoginRequest.builder()
                .authorizationCode(authorizationCode)
                .profile(profile)
                .build();
    }
    
    public boolean isAppleLogin() {
        return authorizationCode != null && !authorizationCode.isBlank();
    }
    
    public String getTokenForProvider(String provider) {
        if ("apple".equalsIgnoreCase(provider) || "APPLE".equals(provider)) {
            return authorizationCode;
        }
        return accessToken;
    }
}
