package com.widyu.domain.auth.dto.request;

public record SocialIntegrationRequest(
        String name,
        String phoneNumber,
        String email,
        String provider,
        String oauthId
) {
}
