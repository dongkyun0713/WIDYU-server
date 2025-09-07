package com.widyu.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SocialLoginResponse(
        @Schema(description = "최초 가입 여부", example = "true") boolean isFirst,
        @Schema(description = "액세스 토큰", example = "abc") String accessToken,
        @Schema(description = "리프레시 토큰", example = "abc") String refreshToken,
        @Schema(description = "사용자 프로필") UserProfile profile,
        @Schema(description = "새로 시도한 소셜 계정 정보") NewSocialAccountInfo newSocialAccountInfo
) {
    public static SocialLoginResponse of(boolean isFirst, String accessToken, String refreshToken, UserProfile profile) {
        return new SocialLoginResponse(isFirst, accessToken, refreshToken, profile, null);
    }

    public static SocialLoginResponse ofWithNewAccount(boolean isFirst, String accessToken, String refreshToken, UserProfile profile, NewSocialAccountInfo newSocialAccount) {
        return new SocialLoginResponse(isFirst, accessToken, refreshToken, profile, newSocialAccount);
    }
}
