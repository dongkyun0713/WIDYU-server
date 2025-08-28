package com.widyu.auth.dto.request;

import lombok.Builder;

@Builder
public record SocialLoginRequest(
        String oAuthProvider,
        String oAuthId,
        String email,
        String name,
        String phoneNumber
) {
    public static SocialLoginRequest of(
            String oAuthProvider,
            String oAuthId,
            String email,
            String name,
            String phoneNumber
    ) {
        return SocialLoginRequest.builder()
                .oAuthProvider(oAuthProvider)
                .oAuthId(oAuthId)
                .email(email)
                .name(name)
                .phoneNumber(phoneNumber)
                .build();
    }
}
