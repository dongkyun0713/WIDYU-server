package com.widyu.authtest.dto.response;

import lombok.Builder;

@Builder
public record SocialClientResponse(
        String oauthId,
        String email,
        String name,
        String phoneNumber
) {
    public static SocialClientResponse of(
            final String oauthId,
            final String email,
            final String name,
            final String phoneNumber
    ) {
        return SocialClientResponse.builder()
                .oauthId(oauthId)
                .email(email)
                .name(name)
                .phoneNumber(phoneNumber)
                .build();
    }
}
