package com.widyu.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NewSocialAccountInfo(
        String provider,
        String email,
        String name,
        String oauthId
) {
    public static NewSocialAccountInfo of(String provider, String email, String name, String oauthId) {
        return new NewSocialAccountInfo(provider, email, name, oauthId);
    }
}
