package com.widyu.auth.dto.response;

public record SocialLoginResponse(
        boolean isFirst,
        TokenPairResponse tokenPair
) {
    public static SocialLoginResponse of(final boolean isFirst, final TokenPairResponse tokenPair) {
        return new SocialLoginResponse(isFirst, tokenPair);
    }
}
