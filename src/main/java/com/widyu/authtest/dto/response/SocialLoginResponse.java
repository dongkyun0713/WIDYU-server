package com.widyu.authtest.dto.response;

import com.widyu.auth.dto.response.TokenPairResponse;

public record SocialLoginResponse(
        boolean isFirst,
        TokenPairResponse tokenPair
) {
    public static SocialLoginResponse of(final boolean isFirst, final TokenPairResponse tokenPair) {
        return new SocialLoginResponse(isFirst, tokenPair);
    }
}
