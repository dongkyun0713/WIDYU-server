package com.widyu.auth.dto.request;

public record SocialIntegrationRequest(
        String name,
        String phoneNumber,
        String email,
        String provider,
        String oauthId
) {
}
