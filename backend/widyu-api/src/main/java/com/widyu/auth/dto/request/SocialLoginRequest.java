package com.widyu.auth.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record SocialLoginRequest(
        String accessToken,
        String authorizationCode,
        String refreshToken,
        @Valid
        AppleProfile profile,
        String platform
) {
    public record AppleProfile(
            @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
            String email,
            @Size(max = 255, message = "이름은 255자 이하여야 합니다.")
            String name
    ) {}
    
    public static SocialLoginRequest of(String accessToken) {
        return SocialLoginRequest.builder()
                .accessToken(accessToken)
                .build();
    }
}
