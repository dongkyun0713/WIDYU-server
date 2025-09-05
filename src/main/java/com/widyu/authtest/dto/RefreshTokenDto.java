package com.widyu.authtest.dto;

public record RefreshTokenDto(
        Long memberId,
        String tokenValue,
        Long ttl
) {
}
