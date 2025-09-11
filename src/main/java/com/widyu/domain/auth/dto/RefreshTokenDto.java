package com.widyu.domain.auth.dto;

public record RefreshTokenDto(
        Long memberId,
        String tokenValue,
        Long ttl
) {
}
