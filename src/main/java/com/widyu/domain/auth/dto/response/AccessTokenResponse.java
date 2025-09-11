package com.widyu.domain.auth.dto.response;

public record AccessTokenResponse(
        String accessToken
) {
    public static AccessTokenResponse of(final String accessToken) {
        return new AccessTokenResponse(accessToken);
    }
}
