package com.widyu.auth.dto;

public record RefreshTokenDto(
        Long memberId,
        String tokenValue,
        Long ttl
) {
}
