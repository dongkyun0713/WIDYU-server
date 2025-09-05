package com.widyu.authtest.dto;

import com.widyu.member.domain.MemberRole;

public record TemporaryTokenDto(
        String temporaryMemberId,
        MemberRole memberRole,
        String tokenValue,
        Long ttl
) {
}
