package com.widyu.auth.dto;

import com.widyu.member.domain.MemberRole;

public record RefreshTokenDto(
        Long memberId,
        MemberRole memberRole,
        String tokenValue,
        Long ttl
) {
}
