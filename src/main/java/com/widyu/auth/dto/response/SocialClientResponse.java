package com.widyu.auth.dto.response;

import lombok.Builder;

@Builder
public record SocialClientResponse(
        String oauthId,
        String email,
        String name,
        String phoneNumber
) {
    public static SocialClientResponse of(
            final String email,
            final String oauthId,
            final String name,
            final String phoneNumber
    ) {
        return SocialClientResponse.builder()
                .email(email)
                .oauthId(oauthId)
                .name(name)
                .phoneNumber(phoneNumber)
                .build();
    }
}
