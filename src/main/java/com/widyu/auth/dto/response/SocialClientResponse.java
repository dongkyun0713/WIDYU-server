package com.widyu.auth.dto.response;

import lombok.Builder;

@Builder
public record SocialClientResponse(
        String oauthId,
        String email,
        String name,
        String phoneNumber,
        String refreshToken
) {
    public static SocialClientResponse of(
            final String oauthId,
            final String email,
            final String name,
            final String phoneNumber
    ) {
        return of(oauthId, email, name, phoneNumber, null);
    }

    public static SocialClientResponse of(
            final String oauthId,
            final String email,
            final String name,
            final String phoneNumber,
            final String refreshToken
    ) {
        return SocialClientResponse.builder()
                .oauthId(oauthId)
                .email(email)
                .name(name)
                .phoneNumber(phoneNumber)
                .refreshToken(refreshToken)
                .build();
    }
}
