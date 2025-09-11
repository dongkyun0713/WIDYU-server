package com.widyu.domain.auth.dto;

import com.widyu.domain.member.entity.MemberRole;
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
