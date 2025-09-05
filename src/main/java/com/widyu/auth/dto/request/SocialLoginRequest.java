package com.widyu.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record SocialLoginRequest(
        @NotBlank(message = "Access token은 필수입니다")
        String accessToken
) {
    public static SocialLoginRequest of(
            String accessToken
    ) {
        return SocialLoginRequest.builder()
                .accessToken(accessToken)
                .build();
    }
}
