package com.widyu.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NewSocialAccountInfo(
        @Schema(description = "소셜 제공자", example = "kakao") String provider,
        @Schema(description = "이메일", example = "user@example.com") String email,
        @Schema(description = "이름", example = "홍길동") String name
) {
    public static NewSocialAccountInfo of(String provider, String email, String name) {
        return new NewSocialAccountInfo(provider, email, name);
    }
}