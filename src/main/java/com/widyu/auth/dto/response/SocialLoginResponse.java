package com.widyu.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SocialLoginResponse(
        boolean isFirst,
        String accessToken,
        String refreshToken,
        UserProfile profile,
        NewSocialAccountInfo newSocialAccountInfo
) {
    public static SocialLoginResponse of(boolean isFirst, String accessToken, String refreshToken,
                                         UserProfile profile) {
        return new SocialLoginResponse(isFirst, accessToken, refreshToken, profile, null);
    }

    public static SocialLoginResponse ofWithNewAccount(boolean isFirst, String accessToken, String refreshToken,
                                                       UserProfile profile, NewSocialAccountInfo newSocialAccount) {
        return new SocialLoginResponse(isFirst, accessToken, refreshToken, profile, newSocialAccount);
    }
}
