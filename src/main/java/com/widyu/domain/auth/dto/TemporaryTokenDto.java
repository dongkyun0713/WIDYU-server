package com.widyu.domain.auth.dto;

import com.widyu.domain.member.entity.MemberRole;

public record TemporaryTokenDto(
        String temporaryMemberId,
        MemberRole memberRole,
        String tokenValue,
        Long ttl
) {
}
