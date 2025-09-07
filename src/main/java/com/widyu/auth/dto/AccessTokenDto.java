package com.widyu.auth.dto;

import com.widyu.member.domain.MemberRole;
import lombok.Builder;

@Builder
public record AccessTokenDto(
        Long memberId,
        MemberRole memberRole,
        String loginType,
        String tokenValue
) {
    public static AccessTokenDto of(final Long memberId, final MemberRole memberRole, final String loginType, final String tokenValue) {
        return AccessTokenDto.builder()
                .memberId(memberId)
                .memberRole(memberRole)
                .loginType(loginType)
                .tokenValue(tokenValue)
                .build();
    }
}
