package com.widyu.authtest.dto;

import com.widyu.domain.member.entity.MemberRole;
import lombok.Builder;

@Builder
public record AccessTokenDto(
        Long memberId,
        MemberRole memberRole,
        String tokenValue
) {
    public static AccessTokenDto of(final Long memberId, final MemberRole memberRole, final String tokenValue) {
        return AccessTokenDto.builder()
                .memberId(memberId)
                .memberRole(memberRole)
                .tokenValue(tokenValue)
                .build();
    }
}
